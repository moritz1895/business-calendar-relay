# business-calendar-relay

Überwacht ein oder mehrere private CalDAV-Kalender und spiegelt deren Termine
als **titellose Blocker** in einen dienstlichen Outlook-Kalender. Der
Transportweg ist iMIP: der Service verschickt `METHOD:REQUEST`- bzw.
`METHOD:CANCEL`-iCal-Mails per SMTP an die dienstliche Adresse; Outlook
rendert sie als Einladung und blockt die Zeit sofort, ohne dass eine Zusage
nötig ist.

Architektur, Coding-Standards und der agentenbasierte Workflow sind in
[`CLAUDE.md`](CLAUDE.md) beschrieben (Hexagonal Architecture, Ports:
`CalendarSource`, `BlockerSink`, `StateStore`).

## Features

- Beliebig viele private CalDAV-Quellkalender in einer einzigen
  Konfiguration (`relay.calendars`), je mit eigener Relay-Historie.
- Titellose Blocker im dienstlichen Kalender — nur Frei/Belegt wird
  gespiegelt, nie der fachliche Anlass des Quelltermins.
- Erstellung, Verschiebung und Absage werden als iMIP `METHOD:REQUEST`/
  `METHOD:CANCEL` unter stabiler `UID` und strikt steigender `SEQUENCE`
  versendet, sodass Outlook sie als denselben, sich entwickelnden Termin
  erkennt statt als neue Einladungen.
- Persistenter Relay-Zustand pro Quellkalender, der Prozessneustarts
  übersteht.
- Programmatisch geplante Poll-Zyklen (kein festes `@Scheduled`-Set), deren
  Anzahl sich automatisch nach der konfigurierten Kalenderliste richtet.
- Fehlertoleranter Poll-Zyklus: ein fehlgeschlagener Versand für einen
  einzelnen Termin blockiert nicht die übrigen Termine desselben Zyklus.

## Tech-Stack

| Bereich | Technologie |
|---|---|
| Sprache/Laufzeit | Java 25 |
| Anwendungs-Framework | Spring Boot 4.0.x (Scheduling, `@ConfigurationProperties`, Actuator) |
| Architektur | Hexagonal Architecture (`ms.rohde:hexagonal-arch-*`) |
| Quellkalender-Zugriff | CalDAV via `java.net.http.HttpClient` (RFC 4791 `calendar-query` REPORT), ICS-Parsing mit `ical4j` |
| iMIP/E-Mail-Versand | SMTP via Spring `JavaMailSender` (Jakarta Mail) |
| Persistenz | Spring Data JPA gegen eingebettetes H2 im Dateimodus |
| Logging | Log4j2 |
| Tests | JUnit 5, Mockito, AssertJ, ArchUnit (`hexagonal-arch-archunit`) |
| Build | Maven |
| Betrieb | Docker / Docker Compose |

## Voraussetzungen

- Java 25 (JDK)
- Maven (oder der mitgelieferte Wrapper, falls vorhanden)
- Zugriff auf ein Maven-Repository mit `ms.rohde:hexagonal-arch-*` in
  Version `1.0.0-SNAPSHOT` (lokal oder privates Repository)
- Ein erreichbarer SMTP-Server für den ausgehenden iMIP-Versand
- Mindestens ein per CalDAV erreichbarer, privater Quellkalender
- Docker und Docker Compose, falls containerisiert betrieben werden soll

## Status

Alle drei Outbound-Adapter (`CalDavCalendarSourceAdapter`, `SmtpBlockerSinkAdapter`,
`JpaStateStoreAdapter`) sowie die Anwendungsverdrahtung stehen: ein programmatischer
Scheduler (`PollAndRelaySchedulerAdapter`) startet nach Anwendungsstart einen
Poll-Zyklus pro konfiguriertem Quellkalender im festen Intervall
(`relay.poll-interval`), die Kalenderliste kommt aus einer einzigen
`relay.calendars`-Konfiguration (siehe unten). Die Struktur der erzeugten
iMIP-Nachrichten ist über Tests festgenagelt (siehe
[`docs/reference/`](docs/reference/) für die zugrunde liegenden, mit Outlook
verifizierten Referenz-Mails). Filterlogik (welche Quelltermine überhaupt gespiegelt
werden) ist bewusst noch nicht gebaut.

## Verhalten (verifiziert gegen Outlook)

- **Neuer Termin** landet unbestätigt als Blocker im Kalender — keine Zusage
  nötig, die Zeit ist sofort geblockt.
- **Geänderter Termin** verschiebt denselben Blocker, es entsteht kein
  Duplikat (gleiche `UID`, höhere `SEQUENCE`).
- **Abgesagter Termin** wird in Outlook als abgesagt markiert. Das
  tatsächliche Löschen aus dem Kalender bleibt bewusst ein manueller
  Schritt — dafür gibt es keinen Workaround.

## Konfiguration

| Variable | Beschreibung | Default |
|---|---|---|
| `SMTP_HOST` | Hostname des SMTP-Relays für ausgehende iMIP-Mails | — (erforderlich) |
| `SMTP_PORT` | SMTP-Port | `587` |
| `SMTP_USERNAME` | SMTP-Benutzername | — (erforderlich) |
| `SMTP_PASSWORD` | SMTP-Passwort | — (erforderlich) |
| `STATE_STORE_DATA_DIR` | Verzeichnis für die eingebettete, dateibasierte H2-Datenbank hinter `StateStore` (muss neustartfest sein — in Docker als Volume mounten) | `./data` |
| `RELAY_POLL_INTERVAL` | Intervall, in dem **jeder** konfigurierte Quellkalender abgefragt wird (Spring-`Duration`-Syntax, z. B. `5m`, `300s`) | `5m` |

Lokale Werte gehören in eine `.env`-Datei (siehe `.env.example`, wird nicht
versioniert).

### Quellkalender (`relay.calendars`)

Die Liste der überwachten Quellkalender wird in **einer** Konfiguration
gepflegt, nicht eine Datei pro Kalender — `relay.calendars` in
`application.yml` (bzw. einer gemounteten Override-Datei/
`SPRING_CONFIG_ADDITIONAL_LOCATION` für den eigentlichen Produktivbestand).
Eine leere Liste ist gültig; die Anwendung startet auch ohne konfigurierte
Kalender (z. B. in CI).

```yaml
relay:
  poll-interval: 5m
  calendars:
    - id: personal-nextcloud
      caldav-url: https://cloud.example.com/remote.php/dav/calendars/user/personal/
      caldav-username: ${CALDAV_PERSONAL_USERNAME}
      caldav-password: ${CALDAV_PERSONAL_PASSWORD}
      organizer-email: ${RELAY_PERSONAL_ORGANIZER_EMAIL}
      attendee-email: ${RELAY_PERSONAL_ATTENDEE_EMAIL}
      from-address: ${RELAY_PERSONAL_FROM_ADDRESS}
      reply-to-address: ${RELAY_PERSONAL_REPLY_TO_ADDRESS}
```

Jeder Eintrag in `relay.calendars`:

| Feld | Beschreibung |
|---|---|
| `id` | Eindeutiger Bezeichner dieses Kalenders. Dient `StateStore` als Persistenz-Schlüssel (`sourceCalendarId`) — **darf nach dem ersten Relay-Lauf niemals umbenannt werden**, sonst verliert die Anwendung die Zuordnung zu allen bereits gespiegelten Terminen dieses Kalenders und behandelt sie beim nächsten Poll fälschlich als neu. |
| `caldav-url` | CalDAV-Collection-URL des Quellkalenders |
| `caldav-username`, `caldav-password` | Basic-Auth-Zugangsdaten für `caldav-url` — nur über Umgebungsvariablen, nie im Klartext einchecken |
| `organizer-email` | Organizer-Adresse, die auf jeden erzeugten Blocker gesetzt wird |
| `attendee-email` | Adresse des dienstlichen Outlook-Postfachs, an das die iMIP-Mail geht |
| `from-address` | `From`/Envelope-From der iMIP-Mail |
| `reply-to-address` | `Reply-To` der iMIP-Mail (i. d. R. die menschliche Adresse des Organizers) |

Jedes Feld ist pro Kalender konfigurierbar — für eine einheitliche Identität
über alle Kalender hinweg genügt es, denselben Wert in jedem Eintrag zu
wiederholen. Für einen weiteren Kalender wird `relay.calendars` um einen
weiteren Eintrag mit eigenen Umgebungsvariablen ergänzt (siehe
`.env.example`).

## Persistenz

`StateStore` (das Relay-Mapping Quell-`UID` → Blocker-`UID`/`SEQUENCE`) wird
über Spring Data JPA in einer eingebetteten H2-Datenbank im Dateimodus
gehalten (`STATE_STORE_DATA_DIR`), nicht in-memory — der Zustand muss einen
Prozessneustart überstehen. Pro konfiguriertem Quellkalender existiert eine
eigene `JpaStateStoreAdapter`-Instanz, gescoped auf dessen `id` aus
`relay.calendars`; alle Instanzen teilen sich dieselbe H2-Datenbank und
`RelayStateJpaRepository`.

## Scheduler

`PollAndRelaySchedulerAdapter` ist der einzige Akteur, der die
Poll-and-Relay-Use-Cases anstößt: nach vollständigem Anwendungsstart
(`ApplicationReadyEvent`, nicht schon während der Bean-Konstruktion) plant er
für jeden konfigurierten Quellkalender einen eigenen, wiederkehrenden Zyklus
mit fixer Verzögerung (`relay.poll-interval`) über einen programmatisch
verwalteten `TaskScheduler` — kein `@Scheduled`, da die Anzahl der
Use-Case-Instanzen erst zur Laufzeit aus `relay.calendars` feststeht. Das
Ergebnis jedes Zyklus wird geloggt: INFO bei einem sauberen Durchlauf, WARN
mit den betroffenen `sourceUid`s, sobald mindestens ein Versand fehlgeschlagen
ist.

## Dokumentation

Dieses README deckt Überblick, Konfiguration und Betrieb ab. Tiefergehende
Dokumentation liegt unter `docs/`:

| Dokument | Inhalt |
|---|---|
| [`docs/domain.md`](docs/domain.md) | Fachliches Domänenmodell: Wertobjekte (`SourceEvent`, `BlockerEvent`, `RelayState`, `RelayAction`), Domänendienste und -regeln (z. B. `SEQUENCE`-Invariante, Aufbewahrung abgesagter Zustände, Titellos-Prinzip). |
| [`docs/use-cases.md`](docs/use-cases.md) | Use-Case-Katalog: "Poll and Relay Source Calendar" mit Akteur, Ablauf, Fehlerfällen und Ergebnis, beschrieben anhand des tatsächlichen Code-Verhaltens. |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records für nicht offensichtliche Entscheidungen (eingebettetes H2 pro Kalender, zufällige `blockerUid`-Generierung, Aufbewahrung abgesagter Relay-Zustände, programmatisches Scheduling, Fehlerisolation im Poll-Zyklus, Bean-Definition-Pruning für pro-Kalender-Komponenten). |
| [`docs/technical/`](docs/technical/) | Technische Implementierungsdetails der Adapter: Datenbank, CalDAV, SMTP, Scheduling, Infrastruktur. |
| [`docs/features/relay-orchestration.md`](docs/features/relay-orchestration.md) | Ursprüngliche Vorab-Spezifikation (Forward-Mode) der Poll-and-Relay-Orchestrierung. `docs/use-cases.md` beschreibt das tatsächliche Verhalten und markiert Abweichungen davon explizit. |
| [`docs/reference/`](docs/reference/) | Anonymisierte, mit Outlook verifizierte iMIP-Referenz-Mails (`.eml`), die die strukturellen Anforderungen an die generierten Nachrichten belegen. |

### Architekturüberblick

Der Service folgt Hexagonal Architecture (siehe [`CLAUDE.md`](CLAUDE.md)):
Die Anwendungsschicht (`core/app`) orchestriert pro konfiguriertem
Quellkalender einen Poll-Zyklus über drei ausgehende Ports —
`CalendarSource` (CalDAV-Lesezugriff), `StateStore` (Relay-Bookkeeping) und
`BlockerSink` (iMIP-Versand per SMTP) — während der reine Domänenkern
(`core/domain`) die Diff-Entscheidung (`RelayDiffPlanner`) und das
iMIP/ICS-Rendering (`ImipCalendarRenderer`) unabhängig von jedem Framework
kapselt. Ein programmatischer Scheduler (`adapters/inbound/scheduling`)
treibt jede Use-Case-Instanz an. Details zum fachlichen Modell stehen in
`docs/domain.md`, zu den Use Cases in `docs/use-cases.md`, zu den
Implementierungsentscheidungen in `docs/adr/` und `docs/technical/`.

## Lokal starten

1. Java 25 und Maven installieren (siehe Voraussetzungen).
2. `.env.example` nach `.env` kopieren und mit echten SMTP- sowie
   CalDAV-Zugangsdaten befüllen (siehe [Konfiguration](#konfiguration)).
3. `mvn clean install` ausführen, um Build und Tests einmal vollständig
   laufen zu lassen.
4. `mvn spring-boot:run` starten (die `relay.calendars`-Liste kann für
   einen ersten Testlauf auch leer bleiben — die Anwendung startet dann
   ohne konfigurierte Quellkalender).
5. Log-Ausgabe beobachten: `PollAndRelaySchedulerAdapter` protokolliert
   nach jedem Poll-Zyklus (`relay.poll-interval`) das Ergebnis pro
   konfiguriertem Quellkalender.

## Bauen & Testen

```bash
mvn clean install          # Build + alle Tests
mvn test                   # Nur Tests
mvn spring-boot:run         # Lokal starten
```

## Docker

```bash
docker compose up --build
```

Hinweis: Der Build zieht `ms.rohde:hexagonal-arch-*` als `1.0.0-SNAPSHOT` aus
dem lokalen Maven-Repository. Für einen containerisierten Build außerhalb
dieser Maschine muss dieses Artefakt aus einem erreichbaren Repository
(privates Repo oder gemountetes `.m2`) verfügbar gemacht werden — das ist
aktuell nicht gelöst.
