# Dokumentation EX-01: Deployment des Spring-Boot-Backends (mit MariaDB) auf AWS EC2 mit GitHub Actions

## 1. Übersicht & Architektur

In **EX-01** wird das Spring-Boot-Backend (`RefCard-03-be-SpringBootBusinessTrips`) über eine mehrstufige **GitHub Actions CI/CD-Pipeline** automatisiert getestet, gebaut und per `rsync`/SSH auf eine **AWS EC2-Instanz** mit lokaler **MariaDB** deployed.

```
[Git Push / PR auf main]
         │
         ▼
┌──────────────────┐
│  1. Job: test    │  ./mvnw test (gegen eingebettete H2 In-Memory DB)
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  2. Job: build   │  ./mvnw clean package -DskipTests → Artefakt "app-jar"
└────────┬─────────┘
         │ (nur bei Push auf main, nicht bei Pull Requests)
         ▼
┌──────────────────┐
│  3. Job: deploy  │  Environment "production" → rsync nach /opt/businesstrips/app.jar
└──────────────────┘  & Neustart des systemd-Services "businesstrips.service"
```

---

## 2. Erklärung des GitHub-Actions-Workflows (`.github/workflows/deploy.yml`)

Die Pipeline liegt unter `.github/workflows/deploy.yml` und wird bei jedem `push` oder `pull_request` auf den Branch `main` ausgelöst.

| Job | Abhängigkeit | Ausführung & Zweck |
|---|---|---|
| **`test`** | – | Richtet **Java 21 (Temurin)** inkl. Maven-Cache ein und führt `./mvnw test` gegen die eingebettete H2-Datenbank aus. Schlägt ein Test fehl, bricht die Pipeline ab. |
| **`build`** | `needs: test` | Baut das ausführbare Spring-Boot-JAR mit `./mvnw clean package -DskipTests` und lädt `target/*.jar` über `actions/upload-artifact@v4` als Artefakt `app-jar` hoch. Es sind keine Build-Variablen nötig, da Spring Boot seine Konfiguration erst zur Laufzeit liest. |
| **`deploy`** | `needs: build` | Läuft **nur bei Pushes auf `main`** (`if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'`) und nutzt das GitHub Environment **`production`**. Lädt `app-jar` herunter, richtet temporär `~/.ssh/deploy_key` ein, überträgt das JAR via `rsync` nach `/opt/businesstrips/app.jar` auf die EC2-Instanz, startet den `systemd`-Service `businesstrips.service` neu und löscht den SSH-Key anschliessend garantiert über `if: always()`. |

---

## 3. Schritt-für-Schritt-Anleitung: AWS EC2 vorbereiten

### 3.1 EC2-Instanz im AWS Learner Lab erstellen

1. **AWS Academy Learner Lab** starten und die AWS Management Console öffnen (**EC2 → Instances → Launch instances**).
2. **Name:** z. B. `biztrips-backend-ec2`
3. **AMI (OS Image):**
   - **Amazon Linux 2023 AMI** (Standard im Learner Lab, SSH-User: `ec2-user`) *oder*
   - **Ubuntu Server 24.04 LTS** (SSH-User: `ubuntu`)
4. **Instance type:** `t2.micro` oder `t3.micro`
5. **Key pair (login):** Bestehendes Key Pair `vockey` (`labsuser.pem` aus *AWS Details → Download PEM*) auswählen oder neues `.pem`-Key-Pair erstellen.
6. **Network settings (Security Group) – WICHTIG:**
   Unter *Firewall (security groups)* folgende **Inbound Rules** aktivieren:
   - **Rule 1:** Type `SSH`, Protocol `TCP`, Port `22`, Source `Anywhere (0.0.0.0/0)`
   - **Rule 2 (`Add security group rule`):** Type `Custom TCP`, Protocol `TCP`, Port `8080`, Source `Anywhere (0.0.0.0/0)`
   *(Ohne Port 8080 läuft jedes spätere `curl` von aussen in einen Timeout!)*
7. Auf **Launch instance** klicken und die **Public IPv4 DNS** (z. B. `ec2-3-93-182-80.compute-1.amazonaws.com`) notieren.

---

### 3.2 Per SSH mit der EC2-Instanz verbinden

Lokal im Terminal (Linux/macOS/Git Bash/WSL):

```bash
chmod 400 /pfad/zu/labsuser.pem
# Bei Amazon Linux: ec2-user | Bei Ubuntu: ubuntu
ssh -i /pfad/zu/labsuser.pem ec2-user@<EC2-PUBLIC-DNS>
```

---

### 3.3 Java 21, `rsync` und MariaDB auf der EC2-Instanz installieren

Führe die folgenden Befehle **in der SSH-Session auf der EC2-Instanz** aus:

#### Variante A: Amazon Linux 2023 (Standard im AWS Learner Lab)
```bash
sudo dnf update -y
sudo dnf install -y java-21-amazon-corretto-headless rsync mariadb105-server
```

#### Variante B: Ubuntu
```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless rsync mariadb-server
```

#### MariaDB starten und Datenbank/User gemäss `application-mariadb.properties` anlegen (beide Systeme)
```bash
sudo systemctl enable --now mariadb

sudo mysql -e "CREATE DATABASE IF NOT EXISTS db_biztrips;"
sudo mysql -e "ALTER USER 'root'@'localhost' IDENTIFIED BY 'bbw123';"
sudo mysql -u root -pbbw123 -e "FLUSH PRIVILEGES;"
```

---

### 3.4 Zielverzeichnis und `systemd`-Service auf EC2 einrichten

Weiterhin in der SSH-Session auf der EC2-Instanz:

```bash
# Zielordner für das Deployment anlegen und dem aktuellen SSH-User zuweisen
sudo mkdir -p /opt/businesstrips
sudo chown -R "$USER":"$USER" /opt/businesstrips

# systemd-Service erstellen (nutzt automatisch aktuellen User und Java-Pfad)
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

# systemd neu laden und Autostart aktivieren
sudo systemctl daemon-reload
sudo systemctl enable businesstrips.service
```

---

## 4. Schritt-für-Schritt-Anleitung: GitHub Environment & Secrets einrichten

### 4.1 GitHub Environment `production` anlegen
1. Öffne das Backend-Repository auf GitHub (`https://github.com/RomanBerli/GitHub-Actions-RefCard03-Backend`).
2. Gehe zu **Settings → Environments → New environment**.
3. Gib exakt den Namen **`production`** ein und klicke auf **Configure environment**.

### 4.2 Environment Secrets hinterlegen
Füge im Environment **`production`** unter **Environment secrets → Add environment secret** folgende drei Secrets hinzu:

| Secret Name | Wert |
|---|---|
| `EC2_HOST` | Public IPv4 DNS oder IP der EC2-Instanz (z. B. `ec2-3-93-182-80.compute-1.amazonaws.com`) |
| `EC2_USER` | `ec2-user` (Amazon Linux) bzw. `ubuntu` (Ubuntu AMI) |
| `EC2_SSH_KEY` | Kompletter Inhalt der `.pem`-Datei inkl. `-----BEGIN RSA PRIVATE KEY-----` und `-----END RSA PRIVATE KEY-----` |

**Alternativ per GitHub CLI (`gh`) im Backend-Ordner:**
```bash
gh secret set EC2_HOST --env production --body "ec2-xx-xx-xx-xx.compute-1.amazonaws.com"
gh secret set EC2_USER --env production --body "ec2-user"
gh secret set EC2_SSH_KEY --env production < /pfad/zu/labsuser.pem
```

---

## 5. Deployment auslösen & testen

### 5.1 Änderungen committen und pushen
```bash
cd backend/RefCard-03-be-SpringBootBusinessTrips
git add .
git commit -m "feat(ci): add EX-01 GitHub Actions workflow for EC2 deployment"
git push origin main
```

> **Hinweis bei Workflow-Push-Fehler (`without workflow scope`):**
> Falls die GitHub CLI den Push von `.github/workflows/deploy.yml` ablehnt:
> ```bash
> gh auth refresh -h github.com -s workflow
> gh auth setup-git
> git push origin main
> ```

### 5.2 Workflow-Run überwachen
```bash
gh run list --limit 5
gh run view --web
```

### 5.3 Funktionstest gegen die EC2-Instanz
Sobald alle 3 Jobs (`test` → `build` → `deploy`) grün sind:

1. **Actuator Health-Check (prüft App + MariaDB-Verbindung):**
   ```bash
   curl -i http://<EC2_HOST>:8080/actuator/health
   ```
   Erwartetes Resultat: `HTTP/1.1 200 OK` mit `{"status":"UP"}`.

2. **Fachlicher REST-Endpoint (`/v1/trips`):**
   ```bash
   curl -i http://<EC2_HOST>:8080/v1/trips
   ```
   Erwartetes Resultat: `HTTP/1.1 200 OK` mit dem JSON-Array der automatisch geseedeten Demo-Business-Trips.

---

## 6. Nachweise / Screenshots (Platzhalter für eigene Screenshots)

> Ergänze hier nach dem Durchlauf deine Screenshots:

- **Commits & GitHub Actions Workflow (`test` → `build` → `deploy` grün):**
  `![GitHub Actions Run](images/ex01-github-actions.png)`
- **GitHub Environment `production` mit Secrets (`EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`):**
  `![GitHub Secrets](images/ex01-github-secrets.png)`
- **EC2-Instanz & systemd-Status (`sudo systemctl status businesstrips.service`):**
  `![EC2 Systemd Status](images/ex01-ec2-systemd.png)`
- **API-Aufruf im Browser / Terminal (`/actuator/health` und `/v1/trips`):**
  `![API Test](images/ex01-api-response.png)`
