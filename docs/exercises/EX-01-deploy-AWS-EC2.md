# EX-01 – Deployment des Spring-Boot-Backends (mit MariaDB) auf AWS EC2 mit GitHub Actions

## Lernziele

Nach dieser Übung könnt ihr:

- ein bestehendes lokales Git-Repository mit einem leeren GitHub-Repository verbinden und pushen
- eine EC2-Instanz für das Hosting eines Spring-Boot-Backends samt MariaDB vorbereiten
- eine GitHub-Actions-Pipeline mit den Jobs **test → build → deploy** nachvollziehen
- die für den Deploy-Job nötigen **Secrets** und **Variables** in GitHub konfigurieren
- einen Workflow-Run beobachten, Fehler lesen und gezielt beheben

## Voraussetzungen

- Ein GitHub-Account mit Zugriff (push-Recht) auf das Ziel-Repository
- `git` und die GitHub CLI (`gh`) lokal installiert, `gh auth login` bereits ausgeführt
- Eine laufende AWS-EC2-Instanz mit einer `.pem`-Datei zum SSH-Login — Ubuntu-AMI oder Amazon-Linux-AMI (z. B. der Default in AWS Academy Learner Lab, dort meist als `labsuser.pem` benannt); Schritt 3 zeigt beide Varianten
- Security Group der Instanz erlaubt eingehenden Traffic auf Port 22 (SSH) und Port 8080 (die REST-API, Spring Boots Standardport) — **am einfachsten schon beim Erstellen der Instanz mitgeben:**
  Im EC2-Launch-Wizard unter *Network settings* ist standardmässig nur eine Regel für SSH (Port 22) vorausgewählt. Dort auf **Add security group rule** klicken und eine zweite Regel ergänzen: Type `Custom TCP`, Port range `8080`, Source `Anywhere` (`0.0.0.0/0`). Ohne diese Regel läuft die Applikation zwar einwandfrei, ist aber von aussen nicht erreichbar — `curl`/Browser hängen dann in einem Timeout, nicht in einer sofortigen Fehlermeldung (das ist das typische Symptom eines fehlenden Security-Group-Eintrags, siehe Stolpersteine in Schritt 6).
  Existiert die Instanz schon ohne diese Regel: *EC2 → Instances → Instance auswählen → Tab „Security" → Security-Group-Link anklicken → Inbound rules → Edit inbound rules → Add rule* (gleiche Werte wie oben). Hat die Instanz mehrere Security Groups (in AWS Academy Learner Lab häufig, da jede gestartete Instanz eine eigene `launch-wizard-N`-Gruppe bekommt), die Regel bei **allen** an der Instanz hängenden Gruppen ergänzen — es reicht, wenn eine davon den Traffic erlaubt, aber ohne die Instanz-Security-Tab-Ansicht zu prüfen weiss man nicht, welche das ist.
- Java 21 lokal installiert (`java -version`), um die Applikation vor dem Deploy lokal bauen/testen zu können

---

## Schritt 1: Lokales Repository mit GitHub verbinden

Falls noch kein GitHub-Repository existiert, legt zuerst ein **leeres** privates Repository an (kein README, keine `.gitignore` — sonst gibt es beim ersten Push Konflikte).

Prüft den aktuell konfigurierten Remote:

```bash
git remote -v
```

Zeigt er auf das falsche Repo oder verwendet er ein Protokoll, für das ihr keinen Zugriff habt (z. B. SSH ohne hinterlegten Key), setzt ihn neu:

```bash
git remote set-url origin https://github.com/<user>/<repo>.git
```

**Stolperstein „Repository not found“:** Diese Meldung erscheint sowohl bei falscher URL als auch bei fehlender Berechtigung. Prüft mit `gh auth status`, welcher GitHub-Account gerade aktiv ist — bei mehreren angemeldeten Accounts kann es sein, dass der falsche aktiv ist:

```bash
gh auth status
gh auth switch --hostname github.com --user <dein-github-user>
```

Danach pushen und den Tracking-Branch setzen:

```bash
git push -u origin main
```

---

## Schritt 2: Workflow-Datei verstehen

Die Pipeline liegt in `.github/workflows/deploy.yml` und besteht aus drei Jobs:

| Job | Zweck |
| --- | --- |
| `test` | `./mvnw test` (JUnit / Spring Boot Test, läuft gegen die eingebettete H2-Datenbank) |
| `build` | `./mvnw clean package -DskipTests`, das Ergebnis aus `target/*.jar` wird als Artefakt hochgeladen |
| `deploy` | lädt das Artefakt, überträgt die `.jar`-Datei per `rsync` über SSH auf die EC2-Instanz und startet den systemd-Service neu |

Wichtige Details im `deploy`-Job:

- `environment: production` — die Secrets für diesen Job werden aus dem GitHub-**Environment** `production` gelesen, nicht aus den allgemeinen Repo-Secrets
- `if: github.ref == 'refs/heads/main' && ...` — der Deploy läuft nur bei einem Push auf `main`, nicht bei Pull Requests
- Der Job schreibt den privaten SSH-Key temporär nach `~/.ssh/deploy_key`, überträgt die Datei via `rsync`, und entfernt den Key am Ende garantiert wieder (`if: always()`)

Lest euch die Datei einmal komplett durch, bevor ihr weitermacht.

> **Unterschied zu einem Frontend-Build:** Bei einer Vite-App würde der `build`-Job Umgebungsvariablen (z. B. eine API-Basis-URL) fest in das JS-Bundle einkompilieren. Spring Boot liest seine Konfiguration (Datenbank-URL, aktives Profil, …) dagegen erst **zur Laufzeit** — über `application*.properties`, Programmargumente oder Umgebungsvariablen. Der `build`-Job hier braucht deshalb keine einzige Repository-Variable; das jar ist zur Build-Zeit noch völlig umgebungsunabhängig.

---

## Schritt 3: EC2-Instanz vorbereiten

Die folgenden Unterschritte werden **einmalig** auf der Instanz ausgeführt.

### 3.1 Mit der Instanz verbinden

```bash
chmod 400 /pfad/zu/deiner-datei.pem
ssh -i /pfad/zu/deiner-datei.pem <user>@<ec2-host>
```

- `<user>` ist **`ubuntu`** bei einer Ubuntu-AMI oder **`ec2-user`** bei einer Amazon-Linux-AMI (siehe Exkurs unten) — derselbe Wert, der später als `EC2_USER`-Secret in Schritt 5 verwendet wird.
- `<ec2-host>` ist die Public DNS oder IP eurer Instanz, z. B. `ec2-3-93-182-80.compute-1.amazonaws.com`.
- `chmod 400` ist nötig, damit SSH die `.pem`-Datei überhaupt akzeptiert — bei zu offenen Dateirechten verweigert SSH den Login mit einer Meldung wie `Permissions 0644 for '...' are too open`.
- Beim allerersten Verbindungsaufbau zu einer neuen Instanz fragt SSH, ob der Host-Fingerprint vertrauenswürdig ist (`Are you sure you want to continue connecting (yes/no/[fingerprint])?`) — mit `yes` bestätigen.

Alle folgenden Befehle (3.2–3.4) werden **in dieser SSH-Sitzung** auf der Instanz ausgeführt, nicht lokal.

> **Exkurs: Amazon Linux statt Ubuntu (z. B. AWS Academy Learner Lab)**
>
> Startet ihr eure Instanz über **AWS Academy Learner Lab**, ist die Default-AMI meist **Amazon Linux**, nicht Ubuntu — erkennbar u. a. am Namen der Schlüsseldatei (`labsuser.pem` statt eines selbst gewählten Namens). Amazon Linux verwendet `dnf`/`yum` statt `apt` und den SSH-User `ec2-user` statt `ubuntu`. Versucht ihr dort `sudo apt update`, bekommt ihr `sudo: apt: command not found`. Die Befehle in 3.2/3.3 sind unten für beide Varianten aufgeführt — welche AMI ihr wirklich habt, verrät `cat /etc/os-release`.

### 3.2 Java-Runtime installieren

**Ubuntu:**

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless rsync
```

**Amazon Linux:**

```bash
sudo dnf update -y
sudo dnf install -y java-21-amazon-corretto-headless rsync
```

### 3.3 MariaDB installieren und einrichten

An dieser Stelle wird MariaDB bewusst **nativ per Paketmanager** installiert, nicht über Docker — Docker ist erst Thema von [EX-02](./EX-02-create-Docker-Image-DockerHub.md). Datenbankname und Credentials werden dabei exakt so gewählt, wie sie schon in `docker/docker-compose.yml` für die lokale Entwicklung stehen, damit dasselbe Spring-Profil (`application-mariadb.properties`) unverändert auch hier funktioniert.

**Ubuntu:**

```bash
sudo apt install -y mariadb-server
```

**Amazon Linux:**

```bash
sudo dnf install -y mariadb105-server
```

> Falls `mariadb105-server` nicht gefunden wird, mit `sudo dnf search mariadb` den tatsächlichen Paketnamen für eure AL-Version nachschauen — er hat sich zwischen Amazon-Linux-2023-Releases schon geändert.

**Beide:**

```bash
sudo systemctl enable --now mariadb

sudo mysql -e "CREATE DATABASE IF NOT EXISTS db_biztrips;"
sudo mysql -e "ALTER USER 'root'@'localhost' IDENTIFIED BY 'bbw123';"
```

> In einem echten Produktionssetup würde man hier einen eigenen, wenig privilegierten Datenbank-User anlegen statt `root` zu verwenden. Für diese Übung wird bewusst dieselbe (einfache) Konfiguration wie lokal verwendet, um den Fokus auf der Pipeline zu halten und nicht auf DB-Berechtigungskonzepten.

### 3.4 Verzeichnis und systemd-Service anlegen

```bash
sudo mkdir -p /opt/businesstrips
sudo chown -R "$USER":"$USER" /opt/businesstrips
```

systemd-Unit unter `/etc/systemd/system/businesstrips.service` anlegen. Statt sie mit einem Editor (`nano`/`vim`) von Hand zu tippen, direkt per Heredoc aus der Shell schreiben — das vermeidet Tippfehler und trägt automatisch den richtigen SSH-User ein (`ubuntu` oder `ec2-user`, je nachdem, mit welchem User ihr gerade per SSH verbunden seid):

```bash
sudo tee /etc/systemd/system/businesstrips.service > /dev/null <<EOF
[Unit]
Description=SpringBoot BusinessTrips Backend
After=network.target mariadb.service

[Service]
User=$(whoami)
WorkingDirectory=/opt/businesstrips
ExecStart=$(which java) -jar /opt/businesstrips/app.jar --spring.profiles.active=mariadb
SuccessExitStatus=143
Restart=on-failure

[Install]
WantedBy=multi-user.target
EOF
```

- `<<EOF` (ohne Anführungszeichen um `EOF`) ist absichtlich ein **nicht-quotiertes** Heredoc — dadurch werden `$(whoami)` und `$(which java)` beim Schreiben der Datei sofort ausgewertet und die tatsächlichen Werte eingetragen, statt die Zeichenketten `$(whoami)`/`$(which java)` wörtlich in die Unit-Datei zu schreiben.
- `$(which java)` löst automatisch den korrekten Pfad auf, egal ob Java über `apt` (Ubuntu) oder `dnf`/Corretto (Amazon Linux) installiert wurde — beide registrieren `java` normalerweise unter `/usr/bin/java`, aber falls nicht, stimmt der Pfad trotzdem.
- Kontrolle, ob die Datei wie erwartet aussieht: `cat /etc/systemd/system/businesstrips.service`.

Aktivieren (noch nicht starten — es liegt noch kein `app.jar` vor, das kommt erst beim ersten Deploy):

```bash
sudo systemctl daemon-reload
sudo systemctl enable businesstrips.service
```

> Der Deploy-Job ruft `sudo` **ohne Passwort** auf (für `systemctl restart`). Das funktioniert bei den AWS-Standard-AMIs — für `ubuntu` genauso wie für `ec2-user` — bereits ohne weiteres Zutun.

---

## Schritt 4: GitHub Environment anlegen

Unter *Settings → Environments* im Repository ein Environment namens **`production`** anlegen (Name muss exakt mit `environment: production` in der Workflow-Datei übereinstimmen). Ohne dieses Environment schlägt der Deploy-Job sofort fehl.

---

## Schritt 5: Secrets konfigurieren

Der Deploy-Job braucht drei **Secrets im Environment `production`** (*Settings → Environments → production → Add secret*, oder per CLI):

| Secret | Beispiel | Beschreibung |
| --- | --- | --- |
| `EC2_HOST` | `ec2-3-93-182-80.compute-1.amazonaws.com` | Public DNS oder IP der Instanz |
| `EC2_USER` | `ubuntu` bzw. `ec2-user` | SSH-Benutzer — `ubuntu` bei Ubuntu-AMI, `ec2-user` bei Amazon-Linux-AMI (siehe Exkurs in Schritt 3.1) |
| `EC2_SSH_KEY` | Inhalt der `.pem`-Datei, vollständig inkl. `-----BEGIN...-----`/`-----END...-----` | Privater SSH-Key |

### Per GitHub CLI setzen

```bash
gh secret set EC2_HOST --env production --body "ec2-3-93-182-80.compute-1.amazonaws.com"
gh secret set EC2_USER --env production --body "ubuntu"
gh secret set EC2_SSH_KEY --env production < /pfad/zu/deiner-datei.pem
```

> Für `EC2_SSH_KEY` bewusst mit `< datei.pem` (Datei als stdin) statt `--body "$(cat datei.pem)"` arbeiten — so landet der Key-Inhalt nie sichtbar im Terminal-Verlauf oder in der Shell-History.

Im Unterschied zu einem Frontend-Beispiel braucht der `build`-Job hier **keine** Repository-Variables (siehe Kasten in Schritt 2) — es müssen also keine zusätzlichen `gh variable set`-Aufrufe gemacht werden.

---

## Schritt 6: Push auslösen und Workflow beobachten

```bash
git push
gh run list --limit 5
gh run view <run-id>
```

Läuft ein Job rot, zeigt euch die genaue Fehlermeldung:

```bash
gh run view <run-id> --log-failed
```

### Bekannte Stolpersteine

**„refusing to allow an OAuth App to create or update workflow `.github/workflows/deploy.yml` without `workflow` scope“**
Der `gh`-Token hat keine Berechtigung, Workflow-Dateien zu pushen. Token-Scope nachträglich erweitern:

```bash
gh auth refresh -h github.com -s workflow
```

Es folgt ein Device-Login-Flow: Code kopieren, `https://github.com/login/device` öffnen, Code eingeben, mit dem richtigen Account bestätigen. Danach `gh auth setup-git` erneut ausführen und pushen.

**Deploy-Job scheitert im Schritt „SSH-Key einrichten“ mit `Process completed with exit code 1`**
Das bedeutet fast immer: eines der drei Secrets (`EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`) ist leer oder fehlt im Environment `production`. Prüft mit:

```bash
gh secret list --env production
```

und ergänzt die fehlenden Werte wie in Schritt 5 beschrieben.

**App startet auf der EC2-Instanz nicht, `systemctl status businesstrips.service` zeigt einen Datenbankfehler**
Meist ist MariaDB (noch) nicht gestartet oder das `ALTER USER`-Passwort aus Schritt 3 stimmt nicht mit `application-mariadb.properties` überein. Prüfen mit:

```bash
sudo systemctl status mariadb
sudo journalctl -u businesstrips.service -n 50 --no-pager
```

**Workflow läuft nicht neu, obwohl Secrets jetzt korrekt sind**
Ihr müsst nicht zwingend einen neuen Commit pushen — ein fehlgeschlagener Run lässt sich gezielt wiederholen:

```bash
gh run rerun <run-id> --failed
```

**Workflow ist grün, `systemctl status businesstrips.service` zeigt `active (running)`, aber `curl http://<EC2_HOST>:8080/...` hängt und liefert nach einiger Zeit einen Timeout (kein `Connection refused`)**
Fast immer eine fehlende Security-Group-Regel für Port 8080 — siehe Voraussetzungen weiter oben. Ein **Timeout** (statt einer sofortigen Fehlermeldung wie `Connection refused`) ist dabei das typische Symptom: Die Pakete werden von der Security Group stumm verworfen, bevor sie überhaupt die Instanz erreichen, die Applikation selbst bekommt davon nichts mit. Zur Kontrolle von aussen (ohne SSH), ob der Port erreichbar ist:

```bash
nc -zv <EC2_HOST> 8080
```

`succeeded` heisst: Port offen, Problem liegt woanders. `Operation timed out` bestätigt die fehlende Security-Group-Regel.

---

## Schritt 7: Ergebnis prüfen

Nach einem grünen Run sollte die API unter `http://<EC2_HOST>:8080` erreichbar sein. Am schnellsten mit dem Actuator-Health-Endpoint prüfen, ob Applikation und Datenbank laufen:

```bash
curl -i http://ec2-3-93-182-80.compute-1.amazonaws.com:8080/actuator/health
```

Erwartet wird `HTTP/1.1 200 OK` mit `{"status":"UP",...}`. Alternativ (oder zusätzlich) direkt die fachliche API prüfen:

```bash
curl -i http://ec2-3-93-182-80.compute-1.amazonaws.com:8080/v1/trips
```

Erwartet wird ein `HTTP/1.1 200 OK` mit einem JSON-Array der (beim Start automatisch eingefügten) Demo-Trips — das bestätigt zusätzlich, dass die REST-API selbst funktioniert, nicht nur die Basis-Infrastruktur.

---

## Reflexionsfragen

1. Warum liegen `EC2_HOST`, `EC2_USER` und `EC2_SSH_KEY` im Environment `production` und nicht als normale Repository-Secrets?
2. Warum braucht der `build`-Job hier keine einzige Repository-Variable, obwohl ein vergleichbarer Frontend-Build (z. B. mit Vite) üblicherweise mindestens eine API-Basis-URL als Build-Variable bräuchte?
3. Was würde passieren, wenn der `deploy`-Job auch bei Pull Requests laufen würde? Warum schützt die Bedingung `if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'` davor?
4. Warum wird der SSH-Key am Ende des Jobs mit `if: always()` wieder gelöscht, statt nur am erfolgreichen Ende?

---

## Musterlösung zu den Reflexionsfragen

> Erst selbst versuchen, dann vergleichen.

**1. Warum liegen `EC2_HOST`, `EC2_USER` und `EC2_SSH_KEY` im Environment `production` und nicht als normale Repository-Secrets?**

Environment-Secrets sind an das Environment gebunden und werden nur Jobs zur Verfügung gestellt, die explizit `environment: production` deklarieren. So kann man zusätzliche Schutzregeln (z. B. Required Reviewers, Wartezeiten, Branch-Einschränkungen) direkt am Environment festmachen, bevor sensible Deploy-Credentials überhaupt gelesen werden dürfen. Bei normalen Repo-Secrets hätte theoretisch jeder Job im Repo (auch versehentlich in einem PR-Workflow) Zugriff darauf.

**2. Warum braucht der `build`-Job hier keine einzige Repository-Variable, obwohl ein vergleichbarer Frontend-Build üblicherweise mindestens eine API-Basis-URL bräuchte?**

Ein Vite-Build kompiliert Umgebungsvariablen zur **Build-Zeit** fest in das erzeugte JS-Bundle ein — danach lassen sie sich nicht mehr ändern, ohne neu zu bauen. Ein Spring-Boot-`.jar` ist dagegen zur Build-Zeit komplett konfigurationsunabhängig: Datenbank-URL, aktives Profil usw. werden erst beim **Start** der Applikation (hier über `--spring.profiles.active=mariadb` in der systemd-Unit) aus `application*.properties`, Programmargumenten oder Umgebungsvariablen gelesen. Genau dasselbe `.jar` könnte unverändert auch mit einem anderen Profil oder einer anderen Datenbank gestartet werden.

**3. Was würde passieren, wenn der `deploy`-Job auch bei Pull Requests laufen würde? Warum schützt die Bedingung davor?**

Ohne diese Bedingung würde jeder PR — auch von einem Fork oder mit noch ungeprüftem Code — potenziell einen Deploy auf die Produktions-EC2-Instanz auslösen, inklusive Zugriff auf den privaten SSH-Key. Das wäre sowohl ein Sicherheitsrisiko (fremder Code bekäme faktisch Zugriff auf Produktionscredentials) als auch fachlich falsch, da PR-Branches oft nicht "production-ready" sind. Die Bedingung `github.ref == 'refs/heads/main' && github.event_name != 'pull_request'` stellt sicher, dass nur tatsächliche Pushes auf `main` deployen.

**4. Warum wird der SSH-Key am Ende des Jobs mit `if: always()` wieder gelöscht, statt nur am erfolgreichen Ende?**

Der Key liegt während des Jobs unverschlüsselt auf dem Runner-Dateisystem. Schlägt ein vorheriger Schritt fehl (z. B. `rsync` bricht ab), würde der restliche Job normalerweise übersprungen — ohne `if: always()` bliebe der Key dann auf dem (kurzlebigen, aber trotzdem fremden) GitHub-Runner zurück, statt garantiert entfernt zu werden. `always()` sorgt dafür, dass der Aufräumschritt unabhängig vom Erfolg vorheriger Schritte ausgeführt wird.
