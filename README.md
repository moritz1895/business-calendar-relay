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
