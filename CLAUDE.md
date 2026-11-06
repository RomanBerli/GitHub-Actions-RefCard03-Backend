# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Spring Boot REST API backend (Java 21, Spring Boot 4.1, Spring Data JPA) for a "business trips" domain
(employees, trips, flights, meetings). It is **RefCard 03** of a CI/CD teaching sequence
(`docs/future/RefCard-03-RefCard-05-own-repos.md`); `docs/exercises/EX-01/02/03` are the actual student
exercises this repo exists to support (EC2 deploy, Docker Hub, ECS+RDS). Application code itself is a
secondary concern to the CI/CD lesson — see "Deliberately imperfect code" below before "fixing" things.

## Commands

```bash
./mvnw test                                              # run tests (H2, in-memory)
./mvnw test -Dtest=SpringBootBusinessTripsv2ApplicationTests   # single test class
./mvnw clean package -DskipTests                          # build target/*.jar
./mvnw spring-boot:run                                    # run with H2 (default profile)

docker compose -f docker/docker-compose.yml up -d         # start MariaDB + phpMyAdmin (localhost:8082)
./mvnw spring-boot:run -Dspring-boot.run.profiles=mariadb  # run against that MariaDB
```

No linter is configured. There's no `.github/copilot-instructions.md`, Cursor rules, or other agent config in this repo.

## Architecture

### Package layout is load-bearing

`BusinessTripsBackendApplication` (`@SpringBootApplication`) lives at `ch.bbw.trips` — component scanning
and JPA entity/repository scanning are rooted there. **Every** controller, entity, repository, DTO,
`@Configuration` class, and `TriptNotFoundAdvice` must stay under `ch.bbw.trips.**` or it silently won't be
picked up (this exact bug — the app class one package level too deep — previously made the app fail to
start with zero autowired beans). If you add a new top-level package outside `ch.bbw.trips`, it will not be
scanned.

Sub-packages: `repo` (entities + controllers + Spring Data repositories, all in one package),
`repo.main` (just `SpringWebConfig` — CORS — and the unused `GeneralConstants`), `dtos`, `ex`
(the 404 exception + `@ControllerAdvice`), `web` (a legacy `HomeController` returning Thymeleaf-style view
names that don't have templates — dead/broken, not part of the REST API).

### Two datasource profiles, one seed data path

- Default profile: embedded H2, ephemeral, reseeded fresh on every start.
- `mariadb` profile (`application-mariadb.properties`): points at `docker/docker-compose.yml`'s MariaDB
  (`db_biztrips` / `root` / `bbw123`, same credentials in both places — keep them in sync if you change one).

`BusinessTripsBackendApplication.demoData()` (a `CommandLineRunner`) seeds Employees/Flights/Trips/Meetings.
It's guarded by `employeeRepository.count() > 0` — restarting against a persistent DB (MariaDB) will **not**
duplicate the seed rows; only fires on a genuinely empty database.

### Entity JSON serialization is a minefield here

`Employee` ↔ `BusinessTrip` is a `@ManyToMany`; `Employee` ↔ `Flight` is `@OneToMany`/`@ManyToOne`. Jackson's
`@JsonManagedReference`/`@JsonBackReference` only work correctly on the latter (tree-shaped) relation — using
them on the many-to-many broke deserialization entirely under Jackson 3.x (Spring Boot 4). Use `@JsonIgnore`
on the many-to-many side instead if you touch these entities. Separately, `Flight.employee` is
`@ToString.Exclude`d — Lombok's generated `Employee.toString()`/`Flight.toString()` otherwise recurse into
each other infinitely (`StackOverflowError`) the moment either gets logged.

`Employee.password` is `@JsonProperty(WRITE_ONLY)` and `Employee.token` is `@JsonIgnore` — deliberate, don't
remove (password hashing is via `PasswordEncoder`/`BCryptPasswordEncoder`, wired as a `@Bean` in the main
application class).

### No authentication is currently enforced

There was a full token-based Spring Security layer (custom filter validating `Authorization: Bearer <token>`
against `Employee.token`) added and then deliberately removed — this repo teaches CI/CD, not auth, and it
was judged a distraction. All `/v1/**` endpoints are open. `POST /v1/signIn` still exists and issues a token,
password hashing still happens, but nothing checks the token on subsequent requests. If you re-add
authorization, remember CORS must be wired through the security filter chain (not just `WebMvcConfigurer`)
or 401/403 responses won't carry CORS headers.

CORS itself lives in `SpringWebConfig` (`ch.bbw.trips.repo.main`), scoped to `/v1/**`.

### Deliberately imperfect code

`docs/reviews/CR-01-initial.md` documents known issues (some fixed since, some not) — e.g. the `Trip[t]`
typo in `TriptNotFoundException`/`TriptNotFoundAdvice`, `Fligth` typo in `FligthDtoMapper`, unused
`TripDtoMapper`/`FligthDtoMapper`/`EmployeeDto`, the broken `web.HomeController`. Several of these are
students' own review/fix targets in the exercise sequence — don't silently "clean up" application code
unless asked; this is a teaching repo where rough edges may be intentional exercise material.

### Git tags mark the teaching sequence

- `v1-start`: student starting point. Deliberately **excludes** `Dockerfile`, `.dockerignore`,
  `.github/workflows/deploy.yml`, and `task-definition.json` — students build these themselves in
  EX-01/02/03.
- `v2-solution`: reference solution, includes all four of the above.

If asked to modify those four CI/CD files, be aware which tag/commit you're on and don't assume they exist.
