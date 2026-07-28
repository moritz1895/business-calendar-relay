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

Frühe Aufbauphase: Projektgerüst steht, die Struktur der erzeugten
iMIP-Nachrichten ist über Tests festgenagelt (siehe
[`docs/reference/`](docs/reference/) für die zugrunde liegenden, mit Outlook
verifizierten Referenz-Mails). Der eigentliche CalDAV-Poller und die
SMTP-Anbindung folgen als nächste Schritte.

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

Lokale Werte gehören in eine `.env`-Datei (siehe `.env.example`, wird nicht
versioniert). Die Liste der überwachten Quellkalender wird über eine eigene
Config-Datei gepflegt (folgt, sobald der `CalendarSource`-Adapter gebaut ist).

## Persistenz

`StateStore` (das Relay-Mapping Quell-`UID` → Blocker-`UID`/`SEQUENCE`) wird
über Spring Data JPA in einer eingebetteten H2-Datenbank im Dateimodus
gehalten (`STATE_STORE_DATA_DIR`), nicht in-memory — der Zustand muss einen
Prozessneustart überstehen. Pro konfiguriertem Quellkalender existiert
konzeptionell eine `StateStore`-Instanz; die eigentliche Verdrahtung mehrerer
Kalender folgt mit dem Scheduler/Multi-Kalender-Konfigurations-PR.

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
