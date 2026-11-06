# EX-02 – Docker-Image bauen, taggen und auf Docker Hub veröffentlichen

## Lernziele

Nach dieser Übung könnt ihr:

- ein Multi-Stage-`Dockerfile` für ein Spring-Boot-Backend schreiben (Maven-Build-Stage → schlankes JRE-Runtime-Image)
- ein Docker-Image lokal bauen und mit einer sinnvollen Tagging-Strategie versehen (`prod01`, `latest`, Git-Kurz-SHA)
- ein Docker-Hub-Repository anlegen und euch per CLI dort anmelden
- ein Image auf Docker Hub pushen und wieder herunterladen
- den Container lokal starten und gegen die bereits laufende MariaDB aus `docker/docker-compose.yml` testen

## Voraussetzungen

- Docker Desktop (oder Docker Engine) lokal installiert und gestartet: `docker --version`
- Ein kostenloser [Docker Hub](https://hub.docker.com/)-Account
- Das Repository aus [EX-01](./EX-01-deploy-AWS-EC2.md) lokal ausgecheckt
- Java 21 lokal installiert (nur zur Kontrolle, der Build läuft später im Container)
- MariaDB lokal per `docker compose -f docker/docker-compose.yml up -d` gestartet (siehe Haupt-`README`/vorherige Übungen)

---

## Schritt 1: `.dockerignore` anlegen

Damit unnötige oder sensible Dateien nicht in den Build-Kontext gelangen, im Projektroot eine Datei `.dockerignore` anlegen:

```
target
.git
.idea
*.iml
docs
docker
```

> Ohne `.dockerignore` würde z. B. der lokale `target/`-Ordner mit in den Build-Kontext kopiert — das macht den Build unnötig langsam und kann alte, lokale Build-Artefakte in den Container schleusen. `target` wird ohnehin frisch **im** Build-Container neu erzeugt.

---

## Schritt 2: Multi-Stage-`Dockerfile` schreiben

Im Projektroot eine Datei `Dockerfile` anlegen:

```dockerfile
# --- Stage 1: Build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline

COPY src ./src
RUN ./mvnw clean package -DskipTests

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Warum zwei Stages?

- **Stage 1 (`build`)** enthält das komplette JDK, den Maven Wrapper und den Quellcode — das brauchen wir nur, um die `.jar`-Datei zu erzeugen.
- **Stage 2 (`jre-alpine`)** enthält am Ende **nur** eine schlanke Java-Runtime (kein Compiler, kein Build-Tool) plus die fertige `.jar`. Quellcode, Maven-Cache und JDK-Compiler landen nicht im finalen Image — das macht es deutlich kleiner und reduziert die Angriffsfläche.
- `COPY .mvn/ .mvn/` und `COPY mvnw pom.xml ./` **vor** `COPY src ./src`: Solange sich `pom.xml` nicht ändert, kann Docker die (langsame) `dependency:go-offline`-Schicht aus dem Layer-Cache wiederverwenden, auch wenn sich nur der Quellcode ändert.

> **Unterschied zu einer Vite-App:** Dort wurden `ARG`s (z. B. eine API-Basis-URL) beim `docker build` fest in das JS-Bundle einkompiliert. Hier gibt es **keine** vergleichbaren `ARG`s/`ENV`s für die Datenbank-Konfiguration im Dockerfile — Spring Boot liest `spring.profiles.active`, `spring.datasource.url` & Co. erst beim `docker run` zur Laufzeit (siehe Schritt 5). Genau dasselbe Image lässt sich damit unverändert gegen unterschiedliche Datenbanken starten.

---

## Schritt 3: Image lokal bauen und taggen

Docker-Hub-Nutzernamen als Variable setzen (Beispiel: `bbwlc`):

```bash
export DOCKERHUB_USER=bbwlc
```

Image bauen und dabei mehrere Tags gleichzeitig vergeben:

```bash
docker build \
  -t "$DOCKERHUB_USER/biztrips-backend:prod01" \
  -t "$DOCKERHUB_USER/biztrips-backend:latest" \
  -t "$DOCKERHUB_USER/biztrips-backend:$(git rev-parse --short HEAD)" \
  .
```

### Tagging-Strategie

| Tag | Zweck |
| --- | --- |
| `prod01` | fester Bezeichner für "das, was aktuell auf Produktionsserver 01 läuft" — wird bei jedem Deploy auf dieses Image umgebogen |
| `latest` | letzter erfolgreicher Build, nicht zwingend deployed |
| `<git-sha>` (z. B. `a1b2c3d`) | unveränderliche, exakt einem Commit zuordenbare Version — wichtig für Rollbacks und Nachvollziehbarkeit |

`latest` allein reicht in der Praxis **nicht**, da es sich bei jedem Push verschiebt und man später nicht mehr weiss, welcher Commit tatsächlich lief. Deshalb immer zusätzlich mit einer unveränderlichen Referenz (Git-SHA) taggen.

---

## Schritt 4: Image lokal testen

Der Container braucht eine erreichbare MariaDB. Am einfachsten: den Container in dasselbe Docker-Netzwerk hängen, in dem `docker/docker-compose.yml` die MariaDB bereits laufen hat (Compose benennt sein Standardnetzwerk nach dem Verzeichnisnamen — hier `docker_default`), und den Hostnamen `bbw-mariadb-trips-dev` aus dem Compose-File direkt als Servicenamen nutzen:

```bash
docker run --rm -p 8080:8080 \
  --network docker_default \
  -e SPRING_PROFILES_ACTIVE=mariadb \
  -e SPRING_DATASOURCE_URL="jdbc:mariadb://bbw-mariadb-trips-dev:3306/db_biztrips" \
  "$DOCKERHUB_USER/biztrips-backend:prod01"
```

```bash
curl -i http://localhost:8080/v1/trips
```

Erwartet wird `HTTP/1.1 200 OK` mit den Demo-Trips als JSON. Mit `Ctrl+C` beenden.

> `SPRING_DATASOURCE_URL` überschreibt hier gezielt nur die URL aus `application-mariadb.properties` (die dort auf `localhost` zeigt, was im Container nicht MariaDB wäre) — Benutzername/Passwort werden weiterhin aus dem Profil gelesen. Das ist dasselbe Prinzip aus Schritt 2: Konfiguration wird zur Laufzeit über Umgebungsvariablen gereicht, nicht im Image fest verdrahtet.

---

## Schritt 5: Bei Docker Hub anmelden

```bash
docker login -u "$DOCKERHUB_USER"
```

Fragt nach einem Passwort oder — empfohlen — einem [Access Token](https://hub.docker.com/settings/security) statt des echten Account-Passworts.

> Docker-Hub-Repository muss vorher nicht manuell angelegt werden: Bei `docker push` auf ein privates Konto wird ein öffentliches Repository automatisch erstellt, sofern der Name noch frei ist. Für ein **privates** Repository legt es vorher explizit unter *Docker Hub → Create Repository* an.

---

## Schritt 6: Image pushen

```bash
docker push "$DOCKERHUB_USER/biztrips-backend:prod01"
docker push "$DOCKERHUB_USER/biztrips-backend:latest"
docker push "$DOCKERHUB_USER/biztrips-backend:$(git rev-parse --short HEAD)"
```

Kontrolle im Browser unter `https://hub.docker.com/r/<user>/biztrips-backend/tags`.

---

## Schritt 7: Image auf einem anderen Rechner testen (optional)

Um zu prüfen, dass wirklich alles im Image steckt und nichts aus dem lokalen Dateisystem "durchgeschummelt" wurde, lokales Image löschen und frisch von Docker Hub ziehen:

```bash
docker rmi "$DOCKERHUB_USER/biztrips-backend:prod01"
docker run --rm -p 8080:8080 \
  --network docker_default \
  -e SPRING_PROFILES_ACTIVE=mariadb \
  -e SPRING_DATASOURCE_URL="jdbc:mariadb://bbw-mariadb-trips-dev:3306/db_biztrips" \
  "$DOCKERHUB_USER/biztrips-backend:prod01"
```

Läuft die App weiterhin fehlerfrei, wurde nichts vergessen.

---

## Bekannte Stolpersteine

**`denied: requested access to the resource is denied` beim `docker push`**
Entweder seid ihr nicht eingeloggt (`docker login`) oder der Image-Name stimmt nicht mit eurem Docker-Hub-Nutzernamen überein. Der Teil vor dem `/` im Tag (`$DOCKERHUB_USER/biztrips-backend`) **muss** exakt eurem Docker-Hub-Benutzernamen (oder einer Organisation, in der ihr Schreibrechte habt) entsprechen.

**Container startet, aber `curl` liefert `Connection refused` / App beendet sich sofort**
Meist kann die Applikation MariaDB nicht erreichen und bricht beim Start ab. Prüft mit `docker logs <container-id>`, ob eine `Unable to connect` bzw. `Communications link failure`-Exception auftaucht — meist fehlt `--network docker_default` oder die MariaDB aus `docker/docker-compose.yml` läuft (noch) nicht (`docker compose -f docker/docker-compose.yml up -d`).

**Build-Fehler beim `RUN ./mvnw ...` wegen fehlendem `.mvn`-Verzeichnis**
`.mvn/wrapper/maven-wrapper.jar` muss mit ins Repository eingecheckt sein und im `COPY`-Befehl des Dockerfiles referenziert sein (Schritt 2) — ohne dieses Verzeichnis kann der Maven Wrapper im Container nicht sich selbst herunterladen/ausführen.

**`docker build` ist bei jeder Codeänderung wieder komplett langsam**
Die `COPY src ./src`-Zeile muss **nach** `COPY .mvn/ mvnw pom.xml` und `RUN ./mvnw dependency:go-offline` stehen (Schritt 2) — sonst invalidiert jede Quellcodeänderung auch den Dependency-Download-Layer.

---

## Schritt 8: Als GitHub-Actions-Job integrieren (im Repo bereits umgesetzt)

Die Schritte 1–6 lassen sich vollständig automatisieren: nach jedem erfolgreichen Deploy auf `main` soll die Pipeline automatisch ein Image bauen, taggen und auf Docker Hub pushen. Dieses Repository enthält die fertige Referenzlösung — schaut euch `Dockerfile`, `.dockerignore` und den `docker`-Job in `.github/workflows/deploy.yml` an, bevor ihr eure eigene Lösung damit vergleicht.

### Job `docker` in `deploy.yml`

```yaml
  docker:
    name: Docker-Image bauen und auf Docker Hub veröffentlichen
    runs-on: ubuntu-latest
    needs: deploy
    if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'
    steps:
      - uses: actions/checkout@v4

      - name: Bei Docker Hub anmelden
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Buildx einrichten
        uses: docker/setup-buildx-action@v3

      - name: Image bauen und pushen
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: |
            ${{ secrets.DOCKERHUB_USERNAME }}/biztrips-backend:prod01
            ${{ secrets.DOCKERHUB_USERNAME }}/biztrips-backend:latest
            ${{ secrets.DOCKERHUB_USERNAME }}/biztrips-backend:${{ github.sha }}
```

Wichtige Design-Entscheidungen:

- **`needs: deploy`** — der Docker-Job läuft erst, nachdem der EC2-Deploy erfolgreich war. Ein grünes Docker-Image bedeutet damit auch "das lief bereits erfolgreich auf Produktion".
- **Kein `build-args`-Block** — im Unterschied zu einer Vite-App gibt es hier nichts, das zur Build-Zeit einkompiliert werden müsste (siehe Kasten in Schritt 2). Der `docker`-Job braucht deshalb keine zusätzlichen Repository-Variables.
- **`github.sha`** statt der lokal in Schritt 3 verwendeten Kurzform (`git rev-parse --short HEAD`) — in Actions ist der volle Commit-SHA direkt als Kontextvariable verfügbar und eindeutig; ein manueller `git`-Aufruf ist nicht nötig.
- **`docker/build-push-action`** statt einzelner `docker build`/`docker push`-Befehle — nutzt automatisch den von `docker/setup-buildx-action` eingerichteten BuildKit-Builder inkl. Layer-Caching und unterstützt Multi-Tag-Pushes in einem Schritt.

### Benötigte zusätzliche GitHub Secrets

Unter *Settings → Secrets and variables → Actions → Secrets* (Repository-Ebene, kein Environment nötig, da hier nicht auf die EC2-Instanz zugegriffen wird):

| Secret | Beispiel | Beschreibung |
| --- | --- | --- |
| `DOCKERHUB_USERNAME` | `bbwlc` | Docker-Hub-Benutzername |
| `DOCKERHUB_TOKEN` | (Access Token) | Unter *Docker Hub → Account Settings → Security → New Access Token* erzeugen — **nicht** das Account-Passwort verwenden |

Per CLI setzen:

```bash
gh secret set DOCKERHUB_USERNAME --body "<dein-dockerhub-user>"
gh secret set DOCKERHUB_TOKEN --body "<dein-access-token>"
```

### Ergebnis prüfen

```bash
gh run view --job=<docker-job-id> --log
```

oder direkt unter `https://hub.docker.com/r/<dockerhub-user>/biztrips-backend/tags` nachsehen, ob `prod01`, `latest` und der Commit-SHA-Tag mit aktuellem Zeitstempel erschienen sind.

---

## Reflexionsfragen

1. Warum ist das finale Image (Stage 2) deutlich kleiner als es wäre, wenn man alles in einem einzigen `FROM eclipse-temurin:21-jdk` bauen und dort auch laufen lassen würde?
2. Warum reicht der Tag `latest` allein nicht aus, um zuverlässig nachzuvollziehen, welcher Commit gerade auf `prod01` läuft?
3. Warum braucht dieses Dockerfile — im Gegensatz zu einem Vite-basierten Frontend-Image — keine `ARG`s für die Datenbank-Konfiguration? Was bedeutet das für die Möglichkeit, dasselbe Image gegen unterschiedliche Umgebungen (z. B. Test- und Produktions-DB) laufen zu lassen?
4. Was ist der Unterschied zwischen dem Deployment-Ansatz aus EX-01 (`.jar` per `rsync` direkt auf eine EC2-Instanz mit installiertem Java/MariaDB) und diesem Docker-basierten Ansatz? Welche Vor- und Nachteile hat Docker hier?
