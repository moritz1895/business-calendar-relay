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
      SMTP_PORT: ${SMTP_PORT:-587}
      SMTP_USERNAME: ${SMTP_USERNAME}
      SMTP_PASSWORD: ${SMTP_PASSWORD}
      STATE_STORE_DATA_DIR: /app/data
    volumes:
      - relay-state-data:/app/data
    restart: unless-stopped

volumes:
  relay-state-data:
```

Es gibt genau einen Service (`app`) und ein Named Volume
(`relay-state-data`), gemountet auf `/app/data` — dasselbe Verzeichnis, das
`STATE_STORE_DATA_DIR` im Container auf `/app/data` setzt und damit die H2-
Datenbankdatei dort ablegt (siehe `database.md` für die genaue
URL-Ableitung). Ohne dieses Volume ginge der gesamte Relay-Zustand
(Quell-`UID` → Blocker-`UID`/`SEQUENCE`-Mapping) bei jedem Container-Neustart
verloren, und jedes bereits gespiegelte Event würde beim nächsten Poll
fälschlich als neu behandelt.

`relay.calendars` selbst ist **nicht** über Compose-Umgebungsvariablen
abgebildet — die Kalenderliste kommt aus einer YAML-Konfiguration (siehe
`README.md`, Abschnitt „Quellkalender“), die für einen produktiven Einsatz
zusätzlich als gemountete Override-Datei oder über
`SPRING_CONFIG_ADDITIONAL_LOCATION` eingebunden werden muss; `docker-compose.yml`
in diesem Repo deckt dafür noch keinen Mount ab.

## `Dockerfile`: mehrstufiger Build

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S relay && adduser -S relay -G relay \
    && mkdir -p /app/data && chown -R relay:relay /app
USER relay

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
```

- **Build-Stage**: `maven:3.9-eclipse-temurin-25`, Java 25. `pom.xml` wird
  vor dem restlichen Quellcode kopiert und `mvn dependency:go-offline`
  separat ausgeführt, damit der Dependency-Download-Layer im Docker-Cache
  bleibt, solange sich `pom.xml` nicht ändert. Tests werden im Image-Build
  nicht ausgeführt (`-DskipTests`) — CI/lokales `mvn clean install` ist die
  Stelle, an der Tests laufen.
- **Runtime-Stage**: schlankes `eclipse-temurin:25-jre-alpine` (nur JRE,
  kein volles JDK, kein Maven). Läuft als eigens angelegter, nicht-privilegierter
  Nutzer `relay` (nicht `root`), mit vorab angelegtem `/app/data` im
  Besitz dieses Nutzers — dort landet der `STATE_STORE_DATA_DIR`-Mount.
  Es wird nur das gebaute Jar aus der Build-Stage kopiert.
- **Healthcheck**: pollt `http://localhost:8080/actuator/health` alle 30s
  (5s Timeout, 15s Startverzögerung, 3 Fehlversuche bis „unhealthy“) und
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
  Container-Lifecycle übersteht (im Compose-Setup: das Named Volume
  `relay-state-data`). Ein versehentlich nicht gemountetes
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

## Bekannte Einschränkung: Dependency-Auflösung für `hexagonal-arch`

Der Maven-Build im Docker-Image (`mvn dependency:go-offline` /
`mvn package`) löst `ms.rohde:hexagonal-arch-annotations`,
`ms.rohde:hexagonal-arch-spring` und `ms.rohde:hexagonal-arch-archunit` in
der Version `1.0.0-SNAPSHOT` auf (siehe `pom.xml`,
`<hexagonal-arch.version>1.0.0-SNAPSHOT</hexagonal-arch.version>`). Diese
Artefakte liegen aktuell **nur** im lokalen Maven-Repository (`~/.m2`) der
aktuellen Entwicklungsmaschine — es gibt kein erreichbares Remote-Repository
(privat oder öffentlich), aus dem sie bezogen werden könnten.

Ein containerisierter Build auf jeder anderen Maschine als der aktuellen
Entwicklungsmaschine schlägt daher aktuell fehl, weil der Docker-Build in
einer isolierten Umgebung ohne Zugriff auf das lokale `~/.m2` läuft und
`1.0.0-SNAPSHOT` nirgends sonst auflösbar ist.

Dies ist eine bekannte, unaufgelöste Lücke — dieses Dokument löst sie nicht;
eine gesonderte, projektunabhängige Anleitung dafür wird separat vorbereitet.
