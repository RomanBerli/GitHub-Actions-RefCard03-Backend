# CR-01 — Initial Code Review: SpringBootBusinessTripsv2

**Date:** 2026-09-11
**Reviewer:** Claude (automated review)
**Scope:** Full repository (`src/main`, `src/test`, `pom.xml`, `docker/docker-compose.yml`)
**Method:** Static reading of all source files, dependency graph analysis, and an actual local run (`./mvnw spring-boot:run`) to verify runtime behavior, not just inspect code.

## TL;DR

The application **does not start** in its current state — verified by actually running it, not just inferred from reading the code. The root cause is a package-layout mistake: the `@SpringBootApplication` class sits in a sub-package of where the rest of the code lives, so Spring Boot's default component scan finds none of the controllers, repositories, entities, or `@Component`/`@ControllerAdvice` beans. Beyond that blocking issue, the codebase has several real security gaps (a non-cryptographic "password hash", full password/token leakage in API responses, no authorization on any endpoint) and a fair amount of leftover tutorial/copy-paste code (shopping-cart naming, dead DTOs/mappers, unreachable endpoints, a Docker Compose stack the app never actually connects to). None of this is surprising for a teaching repository at this stage, but it should be fixed before the project is used as a "working reference" for the CI/CD exercises that deploy it.

---

## 1. Critical — Application fails to start

### 1.1 Component scan does not cover the code (blocking)

`BusinessTripsBackendApplication` (`src/main/java/ch/clip/trips/repo/main/BusinessTripsBackendApplication.java:17`) is annotated `@SpringBootApplication` and lives in package `ch.clip.trips.repo.main`. With no explicit `@ComponentScan`/`@EntityScan`/`@EnableJpaRepositories` anywhere in the codebase (confirmed via grep), Spring Boot scans only `ch.clip.trips.repo.main` and its sub-packages.

Every controller, entity, repository, mapper `@Component`, and `@ControllerAdvice` lives **outside** that subtree:

- `ch.clip.trips.repo` — `BusinessTrip`, `Employee`, `Flight`, `Meeting` (entities), all four `*Controller` classes, all four `*Repository` interfaces (this is the **parent** package of `ch.clip.trips.repo.main`, so scanning never reaches it)
- `ch.clip.trips.dtos` — `TripDtoMapper` (`@Component`)
- `ch.clip.trips.ex` — `TripNotFoundAdvice` (`@ControllerAdvice`)
- `web` — `HomeController`

Reproduced locally:

```
$ ./mvnw -q -o spring-boot:run
...
Finished Spring Data repository scanning in 5 ms. Found 0 JPA repository interfaces.
...
APPLICATION FAILED TO START
Parameter 0 of method demoData in ch.clip.trips.repo.main.BusinessTripsBackendApplication required
a bean of type 'ch.clip.trips.repo.FlightRepository' that could not be found.
```

The lone JUnit test (`SpringBootBusinessTripsv2ApplicationTests.contextLoads()`) does not catch this because it only asserts the context loads, and the context *can* load a minimal set of beans — the failure only appears because `demoData()` explicitly wires in the (unscanned) repositories. If that bean didn't request them, the app would boot "successfully" with **zero** controllers, repositories, or entities registered, silently serving nothing on `/v1/**`.

**Fix:** move `BusinessTripsBackendApplication` up to `ch.clip.trips` (parent of `repo`, `dtos`, `ex`), or add explicit `@ComponentScan(basePackages = "ch.clip.trips")` plus `@EntityScan`/`@EnableJpaRepositories` for the same base package, and move/rename the `web` package under `ch.clip.trips` as well (or add it to the scan).

This is the single highest-priority fix — nothing else in this review can be verified end-to-end until it's resolved.

---

## 2. Security

### 2.1 "Password hashing" is not hashing
`HashCode.generateHashCode` (`src/main/java/ch/clip/trips/repo/HashCode.java:4-10`) reimplements `String.hashCode()` — a 32-bit non-cryptographic hash with a high collision rate, no salt, and no iteration count. It is trivially reversible (rainbow tables, brute force, or even just `String.hashCode()` collisions). Passwords "protected" this way offer essentially no protection at rest.

**Fix:** use Spring Security's `PasswordEncoder` (BCrypt or Argon2) for both storing and verifying passwords.

### 2.2 Passwords and session tokens are returned in API responses
`Employee` (`src/main/java/ch/clip/trips/repo/Employee.java:25-26`) exposes `password` and `token` fields with no `@JsonIgnore`. `EmployeeController.allItems()` (`EmployeeController.java:71-76`) and `.one()` (`:94-97`) return `Employee`/`List<Employee>` directly, so `GET /v1/employees` and `GET /v1/employees/{id}` serialize every user's password hash and current session token to any caller — and there is no authentication guarding these endpoints at all (see 2.3). The already-defined `EmployeeDto` (`src/main/java/ch/clip/trips/dtos/EmployeeDto.java`) has the identical problem (still includes `password`/`token`) and, notably, is unused anywhere in the code.

**Fix:** never serialize `password`/`token` in outbound API responses — mark them `@JsonIgnore`, and introduce a real DTO (not the current `EmployeeDto`) that excludes credential fields for all read endpoints.

### 2.3 No authorization on any endpoint
`signIn` (`EmployeeController.java:32-57`) generates a token on successful login, but nothing in the codebase (no filter, interceptor, or `@PreAuthorize`) ever validates that token on later requests. Every CRUD endpoint — create/update/delete employees, trips, flights, meetings — is reachable by anyone, authenticated or not. Spring Security isn't even a dependency in `pom.xml`.

**Fix:** add Spring Security (or a minimal filter) that requires the issued token on `/v1/**`, and define which endpoints are actually meant to be public.

### 2.4 CORS configuration is effectively dead / inconsistent
`SpringWebConfig` (`src/main/java/ch/clip/trips/repo/main/SpringWebConfig.java:14-20`) registers global CORS mappings for `/ch/clip/trips/repo/main/*`, `/meeting/*`, and `/meeting/items/` — none of which match any real controller path (everything is under `/v1/**`). This global config does nothing useful today; actual CORS behavior comes entirely from ad-hoc, inconsistent `@CrossOrigin` annotations scattered per method with different hardcoded origins (`localhost:3000`, `3001`, `5173`) — e.g. `FlightController` mixes origin `3000` on most methods but `3001` on `emptyFlights()` (`FlightController.java:109`) with no apparent reason. Note this class is currently unreachable anyway due to §1.1; once scanning is fixed this needs cleanup too. Also, `@EnableWebMvc` on this class opts out of Spring Boot's auto-configured MVC defaults (message converters, static resource handling, etc.) — usually undesirable unless there's a specific reason to fully customize MVC.

**Fix:** define CORS once, centrally, against the actual `/v1/**` paths, with an explicit, deliberate origin allowlist (ideally externalized to config, not hardcoded per method); drop `@EnableWebMvc` unless truly needed.

### 2.5 Minor: `Baerer` typo in Authorization header
`EmployeeController.java:51` sends `.header("Authorization", "Baerer "+uuid)` — misspelled "Bearer". Standard HTTP clients/libraries that parse the `Bearer` scheme won't recognize this value.

---

## 3. Correctness / functional bugs

### 3.1 `POST /v1/trips` doesn't exist
`BusinessTripController.newProduct` (`BusinessTripController.java:52-56`) has no mapping annotation at all — the equivalent `@PostMapping("/trips")` block is commented out just above it (lines 60-65). As written, there is no way to create a `BusinessTrip` via the API.

### 3.2 Trip deletion uses the wrong path
`BusinessTripController.deleteProduct` is mapped to `@DeleteMapping("/products/{id}")` (`BusinessTripController.java:99`) — a leftover from a copy-pasted "Product" tutorial — while every other endpoint on this controller uses `/trips`. `DELETE /v1/trips/{id}` does not exist; `DELETE /v1/products/{id}` does, inconsistently.

### 3.3 Seed data can never log in
`BusinessTripsBackendApplication.demoData()` (`BusinessTripsBackendApplication.java:32`) creates employee `giuanne` with plaintext password `"1234"`. `signIn` compares `user.getPassword().equals(HashCode.generateHashCode(rawInput))` (`EmployeeController.java:40`) — since the seed value was never hashed, this comparison can never succeed. The only demo user shipped with the app cannot log in.

### 3.4 Employee "update" endpoint is on the wrong path
`EmployeeController.replaceItem` (PUT) is mapped to `/cart/items/{id}` (`EmployeeController.java:99`) — leftover shopping-cart naming (see also `GeneralConstants.ID_SESSION_SHOPPING_CART`, unused, and method name `emptyCart()` on `EmployeeController.java:132` which actually deletes all employees). There is effectively no update endpoint under `/v1/employees`.

### 3.5 Manually-assigned IDs against an `IDENTITY` strategy
All entities use `@GeneratedValue(strategy = GenerationType.IDENTITY)`, yet `demoData()` constructs every entity with an explicit ID (`new Employee(1L, ...)`, etc.). This happens to work against a fresh H2 in-memory database but is fragile — it silently trusts Hibernate/H2 to accept externally supplied identity-column values, and would behave differently (or fail) against a real database or a second seeding run.

---

## 4. Dead code / unused abstractions

- `TripDtoMapper` (`src/main/java/ch/clip/trips/dtos/TripDtoMapper.java`) is a `@Component` that is never injected or called anywhere; `BusinessTripController` builds `TripDto` manually inline instead, duplicating the same mapping logic.
- `FligthDtoMapper` (`src/main/java/ch/clip/trips/dtos/FligthDtoMapper.java`, note the typo in the class name) is likewise never called; `FlightController` also maps manually inline.
- `EmployeeDto` (`src/main/java/ch/clip/trips/dtos/EmployeeDto.java`) is defined but never used anywhere in the codebase.
- `EmployeeRepository.findByName` / `findByJobTitle` (`EmployeeRepository.java:11-12`) are never called.
- `GeneralConstants.ID_SESSION_SHOPPING_CART` (`GeneralConstants.java:6`) is never referenced.
- Large blocks of commented-out code remain in nearly every controller (old `/products`-based versions, a HATEOAS example, alternate `@RequestMapping` forms) — this obscures which code path is actually live and should be deleted rather than kept as commentary.

## 5. Infrastructure mismatch

`docker/docker-compose.yml` provisions a MariaDB + phpMyAdmin stack, but:
- `pom.xml` has no MariaDB/MySQL JDBC driver dependency (only `h2`, runtime scope).
- `src/main/resources/application.properties` is completely empty (0 bytes) — no `spring.datasource.*` pointing at the compose service.

At runtime the app always falls back to Spring Boot's default in-memory H2 database — confirmed in the local run log (`jdbc:h2:mem:f2807082-...`). The Docker Compose file is effectively disconnected from the application: starting it changes nothing about how the app behaves, and every restart of the app discards all data. If the intent (per the AWS/Docker deployment exercises in `docs/exercises/`) is to run against MariaDB in a real environment, this wiring needs to actually be built.

Related: `web.HomeController` (`src/main/java/web/HomeController.java`) returns view names `"index"` and `"simple"` (lines 34, 42), but there is no `src/main/resources/templates` or static directory in the repo and no template engine dependency in `pom.xml` — these views cannot resolve. This controller also lives in the top-level `web` package rather than under `ch.clip.trips`, compounding the scanning problem from §1.1.

## 6. Minor / style

- `BusinessTrip` combines `@Data` with explicit `@Getter`/`@Setter`/`@ToString` (`BusinessTrip.java:13-20`) — redundant, since `@Data` already generates all three.
- Package-wide typos: `TriptNotFoundException`/`TriptNotFoundAdvice` ("Tript"), `FligthDtoMapper` ("Fligth").
- `application.properties` is empty — no explicit `server.port`, `spring.jpa.hibernate.ddl-auto`, or datasource settings; the app relies entirely on undocumented Spring Boot defaults, which is a rough starting point for a project meant to teach deployment/CI-CD to students.
- `spring.jpa.open-in-view` is left at its (enabled) default, per the startup warning already emitted — worth an explicit decision either way, since entities with lazy `@OneToMany`/`@ManyToMany` are returned directly from some endpoints.
- `<java.version>19</java.version>` in `pom.xml` targets a non-LTS release that is already end-of-life; consider pinning to Java 17 or 21 (LTS) — 21 is confirmed available in this environment already.

## 7. What's already reasonable

- Consistent use of `ResponseEntity` with explicit status codes (200/204) in the `getTrips()`/`getFlights()` read endpoints.
- `TriptNotFoundException` + `@ControllerAdvice` gives a clean, centralized 404 pattern (once it's actually scanned — §1.1).
- Bidirectional JPA relations correctly use `@JsonManagedReference`/`@JsonBackReference` to avoid infinite recursion during serialization.
- DTOs for read paths (`TripDto`, `FlightDto`) are a good instinct for not leaking full entities — just not applied consistently (see §2.2 for `Employee`).

---

## Suggested priority order

1. **§1.1** — fix package layout / component scan (nothing else can be verified until the app can start).
2. **§2.1–2.3** — password hashing, response leakage, and missing authorization (real data-exposure risk once the app does start).
3. **§3.1–3.4** — restore the broken/mis-mapped endpoints (create trip, delete trip, employee update, demo login).
4. **§2.4, §4, §5, §6** — cleanup: CORS config, dead code, Docker/datasource wiring, minor issues.
