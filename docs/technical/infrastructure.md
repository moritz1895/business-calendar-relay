# Infrastruktur: Docker, Compose, Betrieb

Der Service ist als einzelner Spring-Boot-Container ausgelegt, ohne externe
Datenbank oder weitere Dienste — die einzige persistente Ressource ist das
eingebettete H2-Datenverzeichnis (siehe `database.md`).

## Lokal starten

Ohne Docker, direkt gegen die lokale Maven-Installation:

```bash
mvn spring-boot:run
```

Benötigt dieselben Umgebungsvariablen wie unten beschrieben (`SMTP_*`,
`STATE_STORE_DATA_DIR`, `RELAY_POLL_INTERVAL`, sowie mindestens einen
`CALDAV_*`/`RELAY_*_*`-Block, falls `relay.calendars` über eine gemountete
Override-Datei oder `SPRING_CONFIG_ADDITIONAL_LOCATION` befüllt wird — mit
leerer `relay.calendars`-Liste startet die Anwendung auch ohne jede
Kalenderkonfiguration, siehe `application.yml`).

Mit Docker Compose:

```bash
docker compose up --build
```

Das baut das Image über den mehrstufigen `Dockerfile`-Build und startet den
`app`-Service aus `docker-compose.yml` mit Port `8080:8080` und dem
Volume-Mount für die H2-Datendatei.

## `docker-compose.yml`

```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      SMTP_HOST: ${SMTP_HOST}
      SMTP_PORT: ${SMTP_PORT:-465}
      SMTP_USERNAME: ${SMTP_USERNAME}
      SMTP_PASSWORD: ${SMTP_PASSWORD}
      STATE_STORE_DATA_DIR: /app/data
    volumes:
      - ./data:/app/data
    restart: unless-stopped
```

Es gibt genau einen Service (`app`) und einen **Bind-Mount** (`./data` auf
dem Host, nicht ein Docker-named-Volume), gemountet auf `/app/data` —
dasselbe Verzeichnis, das `STATE_STORE_DATA_DIR` im Container auf
`/app/data` setzt und damit die H2-Datenbankdatei dort ablegt (siehe
`database.md` für die genaue URL-Ableitung). Bewusst ein Bind-Mount statt
eines named Volumes: die H2-Datei liegt dadurch als ganz normale,
sichtbare Datei unter `./data/relay-state.mv.db` auf dem Host, statt in
Dockers interner Volume-Verwaltung versteckt zu sein — lässt sich damit
direkt per `scp`/`rsync` zwischen Umgebungen kopieren (siehe `README.md`,
Abschnitt „Datenbank-Datei / Zustand zwischen Umgebungen synchronisieren“).
Der Container läuft als fester, nicht-root User `uid:gid 10001` (siehe
`Dockerfile`); `./data` muss vor dem ersten Start diesem Owner gehören
(`chown 10001:10001 data`), sonst schlägt der Schreibzugriff fehl. Ohne
dieses Mount ginge der gesamte Relay-Zustand (Quell-`UID` →
Blocker-`UID`/`SEQUENCE`-Mapping) bei jedem Container-Neustart verloren,
und jedes bereits gespiegelte Event würde beim nächsten Poll fälschlich
als neu behandelt.

`relay.calendars` selbst ist **nicht** über Compose-Umgebungsvariablen
abgebildet — die Kalenderliste kommt aus einer YAML-Konfiguration (siehe
`README.md`, Abschnitt „Quellkalender“), die für einen produktiven Einsatz
zusätzlich als gemountete Override-Datei oder über
`SPRING_CONFIG_ADDITIONAL_LOCATION` eingebunden werden muss; `docker-compose.yml`
in diesem Repo deckt dafür noch keinen Mount ab.

## `Dockerfile`: mehrstufiger Build

Der Build erzeugt eine GraalVM-native-image-Binary statt eines JVM-Jars —
siehe [`native-image-build.md`](native-image-build.md) für die vollständige
Erklärung (Motivation, Hibernate-Bytecode-Provider-Problem, gemessener
Ressourcenverbrauch). Dieser Abschnitt beschreibt nur die Docker-Mechanik:

```dockerfile
FROM ghcr.io/graalvm/native-image-community:25 AS builder
WORKDIR /app
COPY settings.xml pom.xml ./
RUN mvn -s settings.xml dependency:go-offline -q
COPY src ./src
RUN mvn -s settings.xml -Pnative -DskipTests -Dnet.bytebuddy.experimental=true -q native:compile

FROM ubuntu:24.04 AS runtime
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd -g 10001 relay && useradd -u 10001 -g relay -M -s /usr/sbin/nologin relay \
    && mkdir -p /app/data && chown -R relay:relay /app
USER relay

COPY --from=builder /app/target/business-calendar-relay ./business-calendar-relay

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=12s --start-period=15s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec ./business-calendar-relay $RELAY_NATIVE_OPTS"]
```

- **Build-Stage**: `ghcr.io/graalvm/native-image-community:25` (Oracle Linux
  10.1, glibc 2.39) mit separat installiertem Maven. `pom.xml` wird vor dem
  restlichen Quellcode kopiert und `mvn dependency:go-offline` separat
  ausgeführt, damit der Dependency-Download-Layer im Docker-Cache bleibt,
  solange sich `pom.xml` nicht ändert. Tests werden im Image-Build nicht
  ausgeführt (`-DskipTests`) — CI/lokales `mvn clean install` ist die
  Stelle, an der Tests laufen. Die Auflösung von `ms.rohde:hexagonal-arch-*`
  läuft über `settings.xml` gegen das interne Repository (siehe unten).
- **Runtime-Stage**: `ubuntu:24.04` — glibc-kompatibel zur Builder-Stage
  (beide auf glibc 2.39), mit `ca-certificates` (für SMTP-TLS-Validierung)
  und `curl` (für den Healthcheck) nachinstalliert. Läuft als eigens
  angelegter, nicht-privilegierter Nutzer `relay` (fest `uid:gid 10001`,
  nicht `root`), mit vorab angelegtem `/app/data` im Besitz dieses Nutzers
  — dort landet der `STATE_STORE_DATA_DIR`-Mount. Es wird nur die gebaute
  Binary aus der Build-Stage kopiert, kein JRE/JDK im Runtime-Image.
- **Healthcheck**: pollt `http://localhost:8080/actuator/health` alle 30s
  (12s Timeout, 15s Startverzögerung, 3 Fehlversuche bis „unhealthy“) und
  prüft auf `"status":"UP"` im JSON. Voraussetzung dafür ist die
  Actuator-Exposition in `application.yml`:

  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health
  ```

  Nur der `health`-Endpunkt ist exponiert, keine weiteren Actuator-Pfade.

## Umgebungsvariablen — operative Sicht

Die vollständige Konfigurationstabelle (inkl. `relay.calendars`-Feldern)
steht im `README.md`. Operativ relevant beim Deployment:

- `SMTP_HOST`/`SMTP_PORT`/`SMTP_USERNAME`/`SMTP_PASSWORD` — müssen auf ein
  SMTP-Relay zeigen, das SPF/DKIM für `from-address` grün validiert (siehe
  `smtp.md`), sonst landen iMIP-Mails im Spam oder werden vom Empfänger
  abgelehnt.
- `STATE_STORE_DATA_DIR` — muss auf ein Verzeichnis zeigen, das den
  Container-Lifecycle übersteht (im Compose-Setup: der Bind-Mount `./data`
  auf dem Host). Ein versehentlich nicht gemountetes
  `STATE_STORE_DATA_DIR` führt beim nächsten Neustart zu vollständigem
  Zustandsverlust, ohne dass die Anwendung das erkennt oder meldet — sie
  startet einfach mit einer leeren `relay_state`-Tabelle neu.
- `RELAY_POLL_INTERVAL` — bestimmt die Poll-Last gegen jeden konfigurierten
  CalDAV-Server; ein zu kurzes Intervall bei vielen Kalendern kann den
  Quell-CalDAV-Server unnötig belasten, da jeder Zyklus einen vollständigen
  `calendar-query` ohne Delta-Filterung ausführt (siehe `caldav.md`).
- `CALDAV_<NAME>_USERNAME`/`CALDAV_<NAME>_PASSWORD`,
  `RELAY_<NAME>_ORGANIZER_EMAIL` usw. — ein Variablenblock pro konfiguriertem
  Kalendereintrag in `relay.calendars`; siehe `.env.example` für das
  vollständige Namensschema.

Lokale Werte gehören in eine `.env`-Datei (git-ignoriert, siehe
`.env.example` als Vorlage); niemals reale Zugangsdaten in
`docker-compose.yml`, `application.yml` oder eingecheckte Konfigurationsdateien
schreiben.

## Dependency-Auflösung für `hexagonal-arch`

Der Maven-Build löst `ms.rohde:hexagonal-arch-annotations`,
`ms.rohde:hexagonal-arch-spring` und `ms.rohde:hexagonal-arch-archunit` in
der Version `1.0.0` (final, kein SNAPSHOT mehr — siehe `pom.xml`,
`<hexagonal-arch.version>1.0.0</hexagonal-arch.version>`) über das interne
`internal-releases`-Repository auf, dessen Zugriff `settings.xml` regelt
(dort auch der Mirror-Eintrag, der die interne Adresse gegenüber Maven
Central bevorzugt). `settings.xml` wird im Builder-Stage-Kontext explizit
mitkopiert und über `-s settings.xml` referenziert, damit ein
containerisierter Build auch auf einer Maschine ohne den lokalen `~/.m2`-Cache
funktioniert — vorausgesetzt, diese Maschine erreicht das interne
Repository.
