# EX-03 – Deployment des Spring-Boot-Backends auf AWS ECS (Fargate) mit RDS und GitHub Actions

## Lernziele

Nach dieser Übung könnt ihr:

- den Unterschied zwischen einem direkten EC2-Deployment (EX-01) und einem containerbasierten ECS-Deployment einordnen
- ein Amazon ECR (Elastic Container Registry) Repository anlegen und ein Image dorthin pushen
- eine **RDS**-Instanz für MariaDB anlegen und begründen, warum die Datenbank hier nicht als Fargate-Container läuft
- eine ECS-Task-Definition, einen Cluster und einen Service (Fargate-Launch-Type) anlegen, inklusive Datenbank-Zugangsdaten über AWS Secrets Manager
- einen Application Load Balancer vor einen ECS-Service schalten
- eine GitHub-Actions-Pipeline schreiben, die bei jedem Push auf `main` ein neues Image baut, nach ECR pusht und den ECS-Service aktualisiert (Rolling Deployment)
- AWS-Zugangsdaten per OIDC (statt langlebiger Access Keys) an GitHub Actions vergeben

## Voraussetzungen

- Abgeschlossene [EX-02](./EX-02-create-Docker-Image-DockerHub.md) — das Repository enthält bereits ein funktionierendes `Dockerfile` und `.dockerignore`
- Ein AWS-Account mit Rechten, ECR-, ECS-, RDS-, IAM- und ELB-Ressourcen anzulegen
- AWS CLI lokal installiert und konfiguriert: `aws --version`, `aws sts get-caller-identity`
- `gh` CLI weiterhin eingeloggt (siehe EX-01)

---

## EC2- vs. ECS-Deployment im Vergleich

| | EC2-Deployment (EX-01) | ECS-Deployment (diese Übung) |
| --- | --- | --- |
| **Deploy-Einheit** | Eine `.jar`-Datei, per `rsync` auf einen konkreten Server kopiert | Ein Docker-Image, das aus einer **Task Definition** heraus als Container gestartet wird |
| **Server-Management** | Ihr verwaltet die EC2-Instanz selbst: OS-Updates, Java-/MariaDB-Installation, Prozess am Leben halten | Bei **Fargate** keine Server sichtbar/verwaltbar — AWS betreibt die Rechenkapazität. Bei **EC2-Launch-Type** laufen weiterhin eigene EC2-Instanzen, aber ECS übernimmt das Scheduling der Container darauf |
| **Datenbank** | MariaDB läuft nativ auf derselben EC2-Instanz wie die Applikation | MariaDB läuft auf **RDS**, komplett getrennt von der (stateless) Container-Flotte — siehe Schritt 3 |
| **Skalierung** | Manuell, oder selbst eine Auto Scaling Group + Load Balancer aufbauen | Eingebaut über die ECS-**Service**-Definition (`desiredCount`) + Application Auto Scaling |
| **Self-Healing** | Nicht vorhanden — ein abgestürzter Prozess muss manuell/durch `systemd`s `Restart=on-failure` neu gestartet werden | Der ECS-Service ersetzt automatisch Tasks, die crashen oder den Health-Check nicht bestehen |
| **Deploy-Mechanismus** | SSH-Verbindung, `.jar` kopieren, systemd-Service neu starten | Neues Image nach ECR pushen, Task Definition aktualisieren, `aws ecs update-service` löst ein Rolling Deployment aus — kein SSH nötig |
| **Rollback** | Manuell: alten Build erneut deployen | Alte Task-Definition-Revision erneut als Service-Deployment auswählen |
| **Typische Zugriffskontrolle** | SSH-Key (`EC2_SSH_KEY`) mit vollem Server-Zugriff | IAM-Rolle mit fein granulierten Rechten nur auf ECR/ECS/Secrets-Manager-APIs (idealerweise per OIDC, kein Long-Lived-Key) |
| **Netzwerk** | Direkt gegen die Public-IP/DNS der Instanz | Application Load Balancer + Target Group vor dem Service, Health Checks über die ALB |

Kurz gesagt: EX-01 verschiebt eine `.jar`-Datei auf einen Server, den ihr komplett selbst betreibt (inklusive der dort mitlaufenden Datenbank). Diese Übung verschiebt ein **Image** in eine **Registry** und überlässt Scheduling, Skalierung und Self-Healing der Container der ECS-Kontrollebene — die Datenbank wandert dabei zusätzlich auf einen separaten, verwalteten RDS-Dienst.

---

## Schritt 1: ECR-Repository anlegen

```bash
aws ecr create-repository --repository-name biztrips-backend
```

Notiert euch die zurückgegebene `repositoryUri`, z. B. `123456789012.dkr.ecr.eu-central-1.amazonaws.com/biztrips-backend`.

---

## Schritt 2: IAM-Rolle für GitHub Actions per OIDC einrichten

Statt langlebiger `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` als Secrets zu hinterlegen (Risiko bei Leak), richtet GitHub als OIDC-Identity-Provider in AWS ein und erstellt eine IAM-Rolle, die GitHub Actions per kurzlebigem Token annehmen kann.

```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

Danach eine Rolle `github-actions-biztrips-backend-ecs` mit einer Trust Policy anlegen, die nur auf euer Repository/euren Branch eingeschränkt ist (`repo:<user>/<repo>:ref:refs/heads/main`), und ihr die Policies `AmazonEC2ContainerRegistryPowerUser` sowie eine eingeschränkte ECS-Update-Policy anhängen.

> Details zur Trust-Policy-Syntax: [`aws-actions/configure-aws-credentials`](https://github.com/aws-actions/configure-aws-credentials#configuring-the-role-and-trust-policy) — folgt der dortigen Anleitung, statt die Policy von Hand zu tippen.

---

## Exkurs: Fallback für AWS Academy Learner Lab

Wer diese Übung mit einem **AWS Academy Learner Lab**-Account statt einem
regulären AWS-Account macht, kann Schritt 2 so nicht durchführen: Learner-Lab-
Accounts erlauben kein `iam:CreateOpenIDConnectProvider` und kein
`iam:CreateRole` — es steht nur die vorgegebene `LabRole` zur Verfügung, der
höchstens zusätzliche Policies angehängt werden dürfen. Der Befehl aus
Schritt 2 schlägt dort mit einem `AccessDenied` fehl.

**Fallback:** Statt einer per OIDC angenommenen Rolle die von AWS Academy pro
Lab-Sitzung bereitgestellten temporären Zugangsdaten verwenden. Sie stehen im
Lab unter *AWS Details → AWS CLI* und bestehen aus drei Werten:
`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` und `AWS_SESSION_TOKEN`. Diese
als GitHub Secrets hinterlegen und in Schritt 8 den `configure-aws-
credentials`-Schritt so anpassen:

```yaml
      - name: AWS-Credentials aus Learner-Lab-Session beziehen
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-session-token: ${{ secrets.AWS_SESSION_TOKEN }}
          aws-region: us-east-1
```

`permissions: id-token: write` wird in diesem Fall nicht gebraucht (kein
OIDC-Token-Request), kann aber im Job stehen bleiben.

Wichtiger Unterschied zum OIDC-Ansatz: Diese Zugangsdaten sind an die
Lab-Sitzung gebunden und laufen ab, sobald die Sitzung endet oder neu
gestartet wird — die GitHub Secrets müssen dann manuell mit den neuen Werten
aktualisiert werden. Das ist eine Einschränkung der Lab-Sandbox, kein
empfohlenes Produktions-Pattern: In einem regulären AWS-Account bleibt der
OIDC-Ansatz aus Schritt 2 der richtige Weg, weil er ganz ohne gespeicherte
Zugangsdaten auskommt und nicht manuell erneuert werden muss.

**RDS (Schritt 3) ist von dieser Einschränkung nicht betroffen** — RDS gehört
zu den im Learner Lab freigegebenen Services und lässt sich mit der
vorgegebenen `LabRole` ganz normal anlegen.

---

## Schritt 3: RDS-Instanz für MariaDB anlegen

Die Datenbank läuft hier bewusst **nicht** als weiterer Container im selben Fargate-Task, sondern auf Amazon RDS:

> **Warum RDS statt eines DB-Containers in Fargate?** Fargate hat im `awsvpc`-Netzwerkmodus keinen direkten persistenten Storage (kein EBS-Mount, nur EFS mit deutlich mehr Konfigurationsaufwand). Eine Datenbank als Fargate-Task zu betreiben würde euch mit einem Storage-Problem konfrontieren, das mit der eigentlichen Lektion (Container-Deployment) nichts zu tun hat. RDS übernimmt Storage, Backups und Patching und passt zum Prinzip "AWS verwaltet zustandsbehaftete Ressourcen, die Container-Flotte bleibt stateless".

```bash
aws rds create-db-instance \
  --db-instance-identifier biztrips-backend-db \
  --db-instance-class db.t3.micro \
  --engine mariadb \
  --engine-version 10.11 \
  --master-username admin \
  --master-user-password '<sicheres-passwort>' \
  --allocated-storage 20 \
  --db-name db_biztrips \
  --publicly-accessible false
```

Fertigstellung abwarten (dauert einige Minuten) und den Endpoint notieren:

```bash
aws rds wait db-instance-available --db-instance-identifier biztrips-backend-db

aws rds describe-db-instances \
  --db-instance-identifier biztrips-backend-db \
  --query "DBInstances[0].Endpoint.Address" --output text
```

- `--publicly-accessible false` — die Instanz bekommt keine öffentliche IP, sie ist ausschliesslich innerhalb der VPC erreichbar, analog dazu, dass MariaDB lokal auch nur innerhalb des Docker-Compose-Netzwerks erreichbar war.
- Die Security Group der RDS-Instanz muss eingehenden Traffic auf Port 3306 **nur** von der Security Group der Fargate-Tasks erlauben (`sg-tasks`, wird in Schritt 5 angelegt) — nicht aus dem gesamten Internet.

Das Passwort zusätzlich in Secrets Manager ablegen, damit die Task Definition (Schritt 6) es referenzieren kann, ohne es im Klartext in die Task Definition oder ins Repository zu schreiben:

```bash
aws secretsmanager create-secret \
  --name biztrips-backend-db-password \
  --secret-string '<dasselbe-passwort-wie-oben>'
```

---

## Schritt 4: ECS-Cluster anlegen

```bash
aws ecs create-cluster --cluster-name biztrips-backend-cluster
```

Ein Fargate-Cluster braucht keine eigenen EC2-Instanzen — der Cluster ist zunächst nur ein logischer Namespace für Services/Tasks.

---

## Schritt 5: Application Load Balancer + Target Group

- Eine **Target Group** vom Typ `ip` (Fargate-Tasks bekommen eine ENI mit eigener IP, keine Instance-ID) anlegen, Port **8080** (Spring Boots Standardport), Health-Check-Pfad `/actuator/health`
- Einen **Application Load Balancer** in mindestens zwei Subnets anlegen, Listener auf Port 80 → leitet an die Target Group weiter
- Security Group des ALB: eingehend Port 80 aus dem Internet
- Security Group der Fargate-Tasks (`sg-tasks`): eingehend Port 8080 **nur von der Security Group des ALB**; ausgehend Port 3306 zur Security Group der RDS-Instanz aus Schritt 3

> `/actuator/health` (Spring Boot Actuator) liefert `200 OK` mit `{"status":"UP",...}`, sobald die Applikation läuft **und** die Datenbankverbindung steht — Actuator bringt dafür automatisch einen `db`-Health-Indicator mit, der eine einfache `isValid()`-Prüfung gegen die konfigurierte Datenbank macht, ganz ohne Fachlogik/Business-Daten vorauszusetzen. Das ist der Grund, warum dieser Pfad (statt z. B. `/v1/trips`) der richtige Health-Check-Pfad für die Target Group ist.

---

## Schritt 6: Task Definition schreiben

`task-definition.json` im Projektroot:

```json
{
  "family": "biztrips-backend",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::123456789012:role/ecsTaskExecutionRole",
  "containerDefinitions": [
    {
      "name": "biztrips-backend",
      "image": "123456789012.dkr.ecr.eu-central-1.amazonaws.com/biztrips-backend:latest",
      "portMappings": [{ "containerPort": 8080, "protocol": "tcp" }],
      "essential": true,
      "environment": [
        { "name": "SPRING_PROFILES_ACTIVE", "value": "mariadb" },
        { "name": "SPRING_DATASOURCE_URL", "value": "jdbc:mariadb://<rds-endpoint>:3306/db_biztrips" },
        { "name": "SPRING_DATASOURCE_USERNAME", "value": "admin" }
      ],
      "secrets": [
        {
          "name": "SPRING_DATASOURCE_PASSWORD",
          "valueFrom": "arn:aws:secretsmanager:eu-central-1:123456789012:secret:biztrips-backend-db-password"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/biztrips-backend",
          "awslogs-region": "eu-central-1",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

- `<rds-endpoint>` durch den in Schritt 3 notierten RDS-Endpoint ersetzen.
- **`environment` vs. `secrets`:** `SPRING_DATASOURCE_URL`/`_USERNAME` stehen im Klartext in der Task Definition (kein Geheimnis, nur eine Adresse/ein Benutzername). `SPRING_DATASOURCE_PASSWORD` kommt dagegen über `secrets` aus Secrets Manager — ECS injiziert den Wert erst beim Task-Start als Umgebungsvariable, er steht nie im Klartext in der Task Definition selbst.
- `ecsTaskExecutionRole` ist eine von AWS vorgegebene Standardrolle (`AmazonECSTaskExecutionRolePolicy`), die dem Container erlaubt, das Image von ECR zu ziehen und Logs nach CloudWatch zu schreiben. Für das `secrets`-Feld braucht diese Rolle zusätzlich `secretsmanager:GetSecretValue` auf `biztrips-backend-db-password` — ohne diese zusätzliche Berechtigung schlägt der Task-Start mit einem Berechtigungsfehler fehl.

---

## Exkurs: Muss das Image in ECR liegen?

Nein. ECS/Fargate kann Images grundsätzlich aus jeder Registry ziehen, nicht nur aus ECR — auch aus DockerHub (siehe EX-02). Diese Übung verwendet ECR, weil die `executionRoleArn` den Pull dann ohne zusätzliche Zugangsdaten erlaubt (rein über IAM) und kein Internetzugriff der Tasks nötig ist. Bei DockerHub sieht es je nach Sichtbarkeit des Images anders aus:

**Öffentliches DockerHub-Image**
Einfach die Image-URL direkt in der Task Definition eintragen, z. B. `"image": "docker.io/<user>/biztrips-backend:latest"` — kein ECR-Push-Schritt nötig. Wichtig: Die Fargate-Tasks brauchen dann **Internetzugriff** (Public IP in einem öffentlichen Subnet oder NAT-Gateway), da DockerHub im Gegensatz zu ECR nicht über einen AWS-internen Pfad erreichbar ist. Fehlt das, schlägt der Pull mit `CannotPullContainerError` fehl (siehe Stolpersteine).

**Privates DockerHub-Repo**
Zusätzlich müssen Zugangsdaten hinterlegt werden, nach demselben Muster wie das DB-Passwort in Schritt 3/6:

1. DockerHub-Zugangsdaten als Secret in AWS Secrets Manager anlegen, z. B.:
   ```bash
   aws secretsmanager create-secret \
     --name dockerhub-credentials \
     --secret-string '{"username":"<user>","password":"<token>"}'
   ```
2. In der Task Definition beim Container `repositoryCredentials` referenzieren:
   ```json
   {
     "repositoryCredentials": {
       "credentialsParameter": "arn:aws:secretsmanager:eu-central-1:123456789012:secret:dockerhub-credentials"
     }
   }
   ```
3. Die `executionRoleArn` braucht zusätzlich `secretsmanager:GetSecretValue` auf dieses Secret, sonst schlägt der Pull mit einem Berechtigungsfehler fehl.

Kurz: ECR ist hier die einfachere, tiefer integrierte Lösung ohne separates Credential-Handling — DockerHub funktioniert aber genauso, mit etwas mehr Konfigurationsaufwand.

---

## Schritt 7: ECS-Service anlegen

```bash
aws ecs register-task-definition --cli-input-json file://task-definition.json

aws ecs create-service \
  --cluster biztrips-backend-cluster \
  --service-name biztrips-backend-service \
  --task-definition biztrips-backend \
  --desired-count 2 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-aaa,subnet-bbb],securityGroups=[sg-tasks],assignPublicIp=ENABLED}" \
  --load-balancers "targetGroupArn=arn:aws:elasticloadbalancing:...,containerName=biztrips-backend,containerPort=8080"
```

`desired-count 2` sorgt dafür, dass immer zwei Tasks laufen — fällt eine aus, ersetzt der Service sie automatisch. Das ist der Punkt, an dem ECS sich am deutlichsten von EX-01 unterscheidet: Dort gab es genau **eine** Instanz ohne eingebaute Redundanz. Beide Tasks teilen sich dieselbe RDS-Instanz — das ist der Grund, warum die Datenbank nicht mit skaliert werden muss, wenn die Task-Anzahl erhöht wird.

---

## Schritt 8: GitHub-Actions-Job `deploy-ecs`

Neuer Job in `.github/workflows/deploy.yml`, der auf dem bestehenden `docker`-Job aus EX-02 aufbaut:

```yaml
  deploy-ecs:
    name: Image nach ECR pushen und ECS-Service aktualisieren
    runs-on: ubuntu-latest
    needs: docker
    if: github.ref == 'refs/heads/main' && github.event_name != 'pull_request'
    permissions:
      id-token: write   # notwendig für OIDC
      contents: read
    steps:
      - uses: actions/checkout@v4

      - name: AWS-Credentials via OIDC beziehen
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ vars.AWS_ROLE_ARN }}
          aws-region: eu-central-1

      - name: Bei ECR anmelden
        id: ecr-login
        uses: aws-actions/amazon-ecr-login@v2

      - name: Image bauen und nach ECR pushen
        env:
          ECR_REGISTRY: ${{ steps.ecr-login.outputs.registry }}
          ECR_REPOSITORY: biztrips-backend
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build \
            -t "$ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG" \
            -t "$ECR_REGISTRY/$ECR_REPOSITORY:latest" .
          docker push "$ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG"
          docker push "$ECR_REGISTRY/$ECR_REPOSITORY:latest"

      - name: Task-Definition mit neuem Image aktualisieren
        id: render-task-def
        uses: aws-actions/amazon-ecs-render-task-definition@v1
        with:
          task-definition: task-definition.json
          container-name: biztrips-backend
          image: ${{ steps.ecr-login.outputs.registry }}/biztrips-backend:${{ github.sha }}

      - name: ECS-Service deployen
        uses: aws-actions/amazon-ecs-deploy-task-definition@v2
        with:
          task-definition: ${{ steps.render-task-def.outputs.task-definition }}
          cluster: biztrips-backend-cluster
          service: biztrips-backend-service
          wait-for-service-stability: true
```

Wichtige Design-Entscheidungen:

- **`permissions: id-token: write`** — ohne diese Berechtigung kann der Job kein OIDC-Token anfordern und `configure-aws-credentials` schlägt fehl
- **`wait-for-service-stability: true`** — der Job wartet, bis ECS bestätigt, dass alle neuen Tasks laufen und die alten Tasks abgelöst wurden (Rolling Deployment), statt sofort grün zu melden, während im Hintergrund noch deployed wird
- **Keine Datenbank-Credentials im Workflow** — `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD` stehen bereits fest in `task-definition.json` (bzw. werden von dort über Secrets Manager aufgelöst, siehe Schritt 6). Der Workflow muss dafür nichts Zusätzliches tun.
- Kein SSH-Key, kein `rsync` — die einzige "Zugangsdaten" ist die kurzlebige, auf dieses Repository/diesen Branch eingeschränkte IAM-Rolle

### Benötigte zusätzliche Repository-Variables

| Variable | Beispiel | Beschreibung |
| --- | --- | --- |
| `AWS_ROLE_ARN` | `arn:aws:iam::123456789012:role/github-actions-biztrips-backend-ecs` | Rolle aus Schritt 2 — der `deploy-ecs`-Job liest sie über `${{ vars.AWS_ROLE_ARN }}`, nicht als Klartext in der YAML |

---

## Bekannte Stolpersteine

**`AccessDenied` beim `configure-aws-credentials`-Schritt**
Die Trust Policy der IAM-Rolle ist meist zu eng oder zu weit falsch konfiguriert — prüft, ob `sub` in der Trust Policy exakt `repo:<user>/<repo>:ref:refs/heads/main` entspricht (inkl. korrektem Repo-Namen und Branch).

**`AccessDenied` schon bei `aws iam create-open-id-connect-provider` (Schritt 2)**
Typisch für **AWS Academy Learner Lab**-Accounts — dort ist das Anlegen eigener IAM-Rollen/OIDC-Provider grundsätzlich gesperrt. Siehe den Exkurs oben zum Fallback mit den temporären Learner-Lab-Zugangsdaten. RDS (Schritt 3) ist davon nicht betroffen.

**Task startet, aber Health-Check der Target Group schlägt dauerhaft fehl**
Meist ein Security-Group-Problem: Die Security Group der Tasks muss eingehenden Traffic von der Security Group des ALB auf Port **8080** erlauben (Schritt 5) — nicht umgekehrt, und nicht Port 80.

**Task startet und crasht sofort wieder, Logs zeigen `Communications link failure` oder `Access denied for user`**
Die Task kann RDS nicht erreichen. Prüfen:
- Erlaubt die Security Group der RDS-Instanz Port 3306 von `sg-tasks` (Schritt 3)?
- Stimmt `<rds-endpoint>` in `task-definition.json` mit dem tatsächlichen RDS-Endpoint überein (Schritt 3, `describe-db-instances`)?
- Hat die `executionRoleArn` die Berechtigung `secretsmanager:GetSecretValue` auf `biztrips-backend-db-password` (Schritt 6)?

**`CannotPullContainerError` beim Task-Start**
Die `executionRoleArn` fehlt oder hat nicht die Policy `AmazonECSTaskExecutionRolePolicy` — ohne diese Rolle darf der Task-Agent das Image nicht von ECR ziehen.

**Service bleibt bei "PENDING", `desired-count` wird nie erreicht**
Häufig fehlende `assignPublicIp=ENABLED` bei Tasks in einem öffentlichen Subnet ohne NAT-Gateway — ohne Public IP kann der Task-Agent weder das Image ziehen noch Logs senden.

---

## Reflexionsfragen

1. Welche Aufgaben, die in EX-01 der SSH-Deploy-Schritt (`rsync` + `sudo systemctl restart businesstrips.service`) manuell erledigt hat, übernimmt in dieser Übung die ECS-Kontrollebene automatisch?
2. Warum ist eine über OIDC bezogene, kurzlebige IAM-Rolle sicherer als ein dauerhaft in GitHub Secrets hinterlegter AWS-Access-Key, wie ihn EX-01 in Form von `EC2_SSH_KEY` verwendet?
3. Warum läuft die Datenbank in dieser Übung auf RDS statt als zweiter Container im selben Fargate-Task — obwohl ECS-Task-Definitionen technisch durchaus mehrere Container erlauben?
4. Warum steht `SPRING_DATASOURCE_PASSWORD` in der Task Definition unter `secrets` (Secrets Manager), während `SPRING_DATASOURCE_URL` einfach unter `environment` im Klartext steht?
5. Was passiert mit den zwei laufenden Tasks eines Services, wenn ein `update-service` mit neuer Task-Definition ausgelöst wird — und warum ist das ein Vorteil gegenüber dem Single-Server-Deployment aus EX-01?
6. Warum braucht die Target Group in Schritt 5 den Typ `ip` statt `instance`, obwohl EX-01/EX-02 nie mit Target Groups gearbeitet haben?
