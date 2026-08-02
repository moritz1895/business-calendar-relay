# business-calendar-relay

Überwacht ein oder mehrere private CalDAV-Kalender und spiegelt deren Termine
als **titellose Blocker** in einen dienstlichen Outlook-Kalender. Der
Transportweg ist iMIP: der Service verschickt `METHOD:REQUEST`- bzw.
`METHOD:CANCEL`-iCal-Mails per SMTP an die dienstliche Adresse; Outlook
rendert sie als Einladung und blockt die Zeit sofort, ohne dass eine Zusage
nötig ist.

Architektur, Coding-Standards und der agentenbasierte Workflow sind in
[`CLAUDE.md`](CLAUDE.md) beschrieben (Hexagonal Architecture, Ports:
`CalendarSource`, `BlockerSink`, `StateStore`, `CalendarReplicaStore`).

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
- Erstellungs-Filterung neuer Blocker (Vergangenheits-Cutoff, Ausschluss
  ganztägiger/nicht-beschäftigter/stornierter/auf Samstag oder Sonntag
  fallender Quelltermine, konfigurierbares Wiederholungs-Zeitfenster für
  wiederkehrende Termine), damit ein erster
  Lauf gegen einen Kalender mit mehrjähriger Historie nicht Hunderte
  historischer Termine als iMIP-Flut verschickt. Wirkt ausschließlich auf
  die Neuanlage, nie auf bereits vorhandene Blocker.
- Auflösung von wiederkehrenden Terminen (`RRULE`) inklusive `EXDATE`- und
  `RECURRENCE-ID`-Overrides zu einzelnen, stabil identifizierten
  Vorkommen.
- Burst-Filter für die Erstinitialisierung eines Quellkalenders
  (Anti-Spam-Schutz fürs Business-Postfach, Issue #16): Die vollständige
  Erstanlage-Liste eines noch nie initialisierten Kalenders wird in einer
  Warteschlange persistiert und über mehrere Poll-Zyklen hinweg, begrenzt
  durch ein konfigurierbares, postfachweites Sendebudget, scheibchenweise
  abgearbeitet, statt in einem Rutsch verschickt zu werden.
- Delta-Synchronisation der CalDAV-Quellkalender über RFC 6578
  `sync-collection` mit persistiertem Sync-Token, statt bei jedem Poll die
  komplette Kalender-Collection erneut anzufordern; automatischer Fallback
  auf die vollständige RFC-4791-`calendar-query`-Anfrage, sobald ein Server
  `sync-collection` nicht unterstützt oder ein Sync-Token ungültig wird.
- **Google Calendar als zweiter, gleichberechtigt koexistierender
  Quellkalender-Typ** (`type: google`): pro `relay.calendars[]`-Eintrag
  wählbar, unabhängig von jedem CalDAV-Eintrag im selben Deployment gepollt.
  Liest Termine über die Google Calendar REST API v3 (`events.list`,
  `singleEvents=true` für serverseitig bereits expandierte
  Wiederholungs-Vorkommen), inkrementelle `syncToken`-Synchronisation nach
  demselben Muster wie der CalDAV-Delta-Sync, OAuth-2.0-Access-Token-Erneuerung
  aus einem einmalig beschafften, langlebigen Refresh-Token. Ein bestehendes
  Deployment ohne `type`-Feld ist davon unberührt (Default `caldav`) --
  siehe [`docs/features/google-calendar-integration.md`](docs/features/google-calendar-integration.md).

## Tech-Stack

| Bereich | Technologie |
|---|---|
| Sprache/Laufzeit | Java 25 |
| Anwendungs-Framework | Spring Boot 4.0.x (Scheduling, `@ConfigurationProperties`, Actuator) |
| Architektur | Hexagonal Architecture (`ms.rohde:hexagonal-arch-*`) |
| Quellkalender-Zugriff | CalDAV via `java.net.http.HttpClient` — Standardweg ist RFC 6578 `sync-collection` REPORT mit persistiertem Sync-Token (Delta-Sync), automatischer Fallback auf die vollständige RFC 4791 `calendar-query` REPORT bei fehlender Serverunterstützung oder `delta-sync-enabled: false`; ICS-Parsing mit `ical4j`. Alternativ Google Calendar REST API v3 via `java.net.http.HttpClient` + Jackson (`type: google`), koexistierend im selben Deployment. |
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

Alle vier Outbound-Adapter (`CalDavCalendarSourceAdapter`,
`SmtpBlockerSinkAdapter`, `JpaStateStoreAdapter`,
`JpaCalendarReplicaStoreAdapter`) sowie die Anwendungsverdrahtung stehen: ein
programmatischer Scheduler (`PollAndRelaySchedulerAdapter`) startet nach
Anwendungsstart einen Poll-Zyklus pro konfiguriertem Quellkalender im festen
Intervall (`relay.poll-interval`), die Kalenderliste kommt aus einer
einzigen `relay.calendars`-Konfiguration (siehe unten). Die Struktur der
erzeugten iMIP-Nachrichten ist über Tests festgenagelt (siehe
[`docs/reference/`](docs/reference/) für die zugrunde liegenden, mit Outlook
verifizierten Referenz-Mails).

Drei Features sind auf dieser Basis inzwischen produktiv umgesetzt:

- **Event-Filterung** für das initiale Handling großer Kalenderhistorien
  (Issue #3): Ein Quelltermin ohne vorherigen Relay-Zustand wird nur dann
  als neuer Blocker angelegt, wenn sein Start in der Zukunft liegt, er kein
  ganztägiger und kein als "nicht beschäftigt" markierter Termin ist, er
  nicht storniert markiert ist, sein Start nicht auf einen Samstag oder
  Sonntag fällt, und — bei wiederkehrenden Terminen — innerhalb eines
  konfigurierbaren, nach vorne gleitenden Zeitfensters
  (`relay.recurring-event-horizon`) liegt. Bereits vorhandene Blocker werden
  von diesem Filter nie betroffen, siehe
  [`docs/features/event-filtering.md`](docs/features/event-filtering.md) und
  [`docs/domain.md`](docs/domain.md) für die vollständige Regel.
- **Burst-Filter für die Erstinitialisierung** eines Quellkalenders (Issue
  #16): Die vollständige Erstanlage-Liste, die beim allerersten Poll-Zyklus
  eines Kalenders anfällt, wird einmalig in einer Warteschlange persistiert
  und über mehrere Poll-Zyklen hinweg, begrenzt durch ein konfigurierbares,
  postfachweites Sendebudget (`relay.initialization.*`), scheibchenweise
  abgearbeitet, statt sofort komplett verschickt zu werden — siehe
  [`docs/features/burst-filter-initialization.md`](docs/features/burst-filter-initialization.md).
- **CalDAV-Delta-Sync via `sync-collection`** (RFC 6578): Statt bei jedem
  Poll die komplette Kalender-Collection erneut per `calendar-query`
  abzufragen, hält der Adapter pro Kalender eine lokale Replik der rohen
  CalDAV-Ressourcen (`CalendarReplicaStore`) und aktualisiert sie anhand
  eines persistierten Sync-Tokens inkrementell; ein Server ohne
  `sync-collection`-Unterstützung oder ein ungültig gewordener Token lösen
  automatisch einen Fallback bzw. Full-Resync aus (siehe ADR-011). Der
  `CalendarSource`-Vertrag ("immer ein vollständiger Snapshot") bleibt dabei
  unverändert — siehe
  [`docs/features/delta-sync.md`](docs/features/delta-sync.md).
- **Google Calendar als zweiter, koexistierender Quellkalender-Typ**: Ein
  `relay.calendars[]`-Eintrag mit `type: google` liest Termine über die
  Google Calendar REST API v3 (`GoogleCalendarSourceAdapter`,
  `events.list` mit `singleEvents=true`) statt über CalDAV, unabhängig
  gepollt neben jedem CalDAV-Eintrag im selben Deployment. Inkrementelle
  Synchronisation über einen persistierten `syncToken`
  (`GoogleCalendarReplicaStore`/`JpaGoogleCalendarReplicaStoreAdapter`,
  strukturell parallel zu `CalendarReplicaStore`) plus ein zusätzlicher,
  horizon-begrenzter Ergänzungs-Fetch, der rein zeitbedingt neu ins Fenster
  gerutschte Vorkommen langlaufender wiederkehrender Serien erfasst. OAuth
  2.0: ein einmalig vom Deployer beschaffter, langlebiger Refresh-Token wird
  zur Laufzeit ausschließlich gegen kurzlebige Access-Token getauscht (In-Memory
  gecacht), kein interaktiver Consent-Flow in der laufenden Anwendung. Ein
  bestehendes Deployment ohne `type`-Feld ist unverändert weiterhin CalDAV
  (Zero-Config-Migration) — siehe
  [`docs/features/google-calendar-integration.md`](docs/features/google-calendar-integration.md).

**Noch nicht umgesetzt, nur entworfen:** Replica Retirement (periodisches
Aufräumen alter `CalendarReplicaStore`-/`RelayState`-Einträge) liegt als
fertig ausgearbeitetes, aber bewusst nicht eingeplantes Design vor — siehe
[`docs/features/replica-retirement.md`](docs/features/replica-retirement.md)
für den Grund (heutige Speicherlast ist vernachlässigbar) und den Umfang.

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
| `SMTP_PORT` | SMTP-Port. Erwartet implizites TLS ("SMTP + SSL/TLS"), nicht STARTTLS — siehe `docs/technical/smtp.md` | `465` |
| `SMTP_USERNAME` | SMTP-Benutzername | — (erforderlich) |
| `SMTP_PASSWORD` | SMTP-Passwort | — (erforderlich) |
| `STATE_STORE_DATA_DIR` | Verzeichnis für die eingebettete, dateibasierte H2-Datenbank hinter `StateStore` (muss neustartfest sein — in Docker als Volume mounten) | `./data` |
| `RELAY_POLL_INTERVAL` | Intervall, in dem **jeder** konfigurierte Quellkalender abgefragt wird (Spring-`Duration`-Syntax, z. B. `5m`, `300s`) | `5m` |
| `RELAY_RECURRING_EVENT_HORIZON` | Wie weit ein Vorkommen eines wiederkehrenden Quelltermins in der Zukunft liegen darf, um noch erstmals als Blocker angelegt zu werden — gilt **einheitlich** für jeden konfigurierten Quellkalender (ISO-8601-`Period`-Syntax, z. B. `P6M`, `P90D`). Einzeltermine sind davon nicht betroffen, siehe [`docs/domain.md`](docs/domain.md). | `P6M` |
| `RELAY_INITIALIZATION_BURST_SIZE` | Wie viele Erstanlagen pro `RELAY_INITIALIZATION_BURST_INTERVAL` beim einmaligen Initialisieren eines Quellkalenders (Issue #16, Anti-Spam-Schutz fürs Business-Postfach) höchstens verschickt werden dürfen — **postfachweit**, über alle konfigurierten Quellkalender zusammengerechnet, keine Pro-Kalender-Einstellung. Wirkt ausschließlich auf die einmalige Erstinitialisierung, siehe [`docs/features/burst-filter-initialization.md`](docs/features/burst-filter-initialization.md). | `5` |
| `RELAY_INITIALIZATION_BURST_INTERVAL` | Fenstergröße des Sendebudgets aus `RELAY_INITIALIZATION_BURST_SIZE` (Spring-`Duration`-Syntax, z. B. `PT1H`, `30m`) — ebenfalls postfachweit und global. | `PT1H` |
| `RELAY_ORGANIZER_EMAIL` | Organizer-Adresse, die auf jeden erzeugten Blocker gesetzt wird — **einheitlich für jeden konfigurierten Kalender**, unabhängig von dessen Typ, kein Per-Kalender-Override (siehe [`docs/features/relay-config-consolidation.md`](docs/features/relay-config-consolidation.md)). | — (erforderlich) |
| `RELAY_ATTENDEE_EMAIL` | Adresse des dienstlichen Outlook-Postfachs, an das die iMIP-Mail geht — ebenfalls global, ein Wert für alle Kalender. | — (erforderlich) |
| `RELAY_FROM_ADDRESS` | `From`/Envelope-From der iMIP-Mail — ebenfalls global. | — (erforderlich) |
| `RELAY_REPLY_TO_ADDRESS` | `Reply-To` der iMIP-Mail (i. d. R. die menschliche Adresse des Organizers) — ebenfalls global. | — (erforderlich) |

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
  recurring-event-horizon: P6M

  organizer-email: ${RELAY_ORGANIZER_EMAIL}
  attendee-email: ${RELAY_ATTENDEE_EMAIL}
  from-address: ${RELAY_FROM_ADDRESS}
  reply-to-address: ${RELAY_REPLY_TO_ADDRESS}

  google-credentials:
    - id: personal-google-account
      client-id: ${GOOGLE_PERSONAL_CLIENT_ID}
      client-secret: ${GOOGLE_PERSONAL_CLIENT_SECRET}
      refresh-token: ${GOOGLE_PERSONAL_REFRESH_TOKEN}

  calendars:
    - id: personal-nextcloud
      # type: caldav   -- optional, das ist ohnehin der Default; bestehende
      # Deployments ohne dieses Feld sind von der Google-Calendar-Integration
      # unberührt (Zero-Config-Migration)
      caldav-url: https://cloud.example.com/remote.php/dav/calendars/user/personal/
      caldav-username: ${CALDAV_PERSONAL_USERNAME}
      caldav-password: ${CALDAV_PERSONAL_PASSWORD}
    - id: personal-google-primary
      type: google
      google-calendar-id: ${GOOGLE_PERSONAL_CALENDAR_ID}
      google-credentials-id: personal-google-account
    - id: personal-google-secondary
      type: google
      google-calendar-id: ${GOOGLE_SECONDARY_CALENDAR_ID}
      google-credentials-id: personal-google-account
```

`organizer-email`/`attendee-email`/`from-address`/`reply-to-address` sind
**global**, ein einziger Satz für jeden konfigurierten Kalender unabhängig
von dessen Typ — bewusst kein Per-Kalender-Override (siehe
[`docs/features/relay-config-consolidation.md`](docs/features/relay-config-consolidation.md)).
Alle drei Kalendereinträge (ein CalDAV- und zwei Google-Einträge, letztere
mit demselben geteilten `google-credentials`-Set) werden vom selben
`PollAndRelaySchedulerAdapter` unabhängig im konfigurierten
`relay.poll-interval` gepollt — beliebige Kombinationen aus CalDAV- und
Google-Einträgen koexistieren im selben Deployment, jeder mit eigener
Relay-Historie.

Jeder Eintrag in `relay.google-credentials`:

| Feld | Beschreibung |
|---|---|
| `id` | Frei wählbarer, stabiler Bezeichner dieses Credential-Sets, referenziert von `relay.calendars[].google-credentials-id`. Muss innerhalb von `relay.google-credentials` eindeutig sein. |
| `client-id`, `client-secret` | Die vom Deployer selbst in der Google Cloud Console angelegten OAuth-2.0-Client-Credentials — siehe [`docs/technical/google-calendar-setup.md`](docs/technical/google-calendar-setup.md) für den einmaligen Einrichtungsschritt. |
| `refresh-token` | Der einmalig per OAuth-Playground-Consent beschaffte, langlebige Refresh-Token — wie ein CalDAV-Passwort ausschließlich über eine Umgebungsvariable, nie im Klartext eingecheckt. |

Jeder Eintrag in `relay.calendars`:

| Feld | Beschreibung |
|---|---|
| `id` | Eindeutiger Bezeichner dieses Kalenders. Dient `StateStore` als Persistenz-Schlüssel (`sourceCalendarId`) — **darf nach dem ersten Relay-Lauf niemals umbenannt werden**, sonst verliert die Anwendung die Zuordnung zu allen bereits gespiegelten Terminen dieses Kalenders und behandelt sie beim nächsten Poll fälschlich als neu. |
| `type` | `caldav` (Default) oder `google`. Fehlt das Feld, bindet Spring Boot automatisch auf `caldav` — Zero-Config-Migration für jedes bestehende Deployment. |
| `caldav-url` | CalDAV-Collection-URL des Quellkalenders. Pflicht nur bei `type: caldav`. |
| `caldav-username`, `caldav-password` | Basic-Auth-Zugangsdaten für `caldav-url` — nur über Umgebungsvariablen, nie im Klartext einchecken. Pflicht nur bei `type: caldav`. |
| `google-calendar-id` | Google-Kalender-ID (bei einem persönlichen Konto meist die Gmail-Adresse selbst, oder eine über Google Calendars "Kalender integrieren"-Einstellungsseite ermittelte ID für einen sekundären Kalender). Pflicht nur bei `type: google`. |
| `google-credentials-id` | Referenziert ein `relay.google-credentials[].id` — löst zur Laufzeit in `RelayWiringConfiguration` in das zugehörige `client-id`/`client-secret`/`refresh-token`-Tripel auf. Pflicht nur bei `type: google`; eine unauflösbare Referenz bricht den Anwendungsstart mit einer Validierungsmeldung ab (siehe [`docs/features/relay-config-consolidation.md`](docs/features/relay-config-consolidation.md)). |
| `delta-sync-enabled` | Ob der konfigurierte Adapter für diesen Kalender seinen protokollspezifischen Delta-Sync-Mechanismus verwenden darf (RFC-6578-`sync-collection` bei CalDAV, `syncToken`-basiert bei Google — siehe [`docs/features/delta-sync.md`](docs/features/delta-sync.md) und [`docs/features/google-calendar-integration.md`](docs/features/google-calendar-integration.md)). Bei CalDAV wird ein Server, der `sync-collection` nicht unterstützt, bereits automatisch erkannt und fällt selbstständig auf `calendar-query` zurück — dieses Feld ist dort ein manueller Notausschalter für den selteneren Fall, dass diese automatische Erkennung selbst nicht wie erwartet funktioniert. Optional, Default `true`. |

Für einen weiteren Kalender wird `relay.calendars` um einen weiteren
Eintrag mit eigenen Umgebungsvariablen ergänzt (siehe `.env.example`); ein
weiteres Google-Konto bekommt einen weiteren `relay.google-credentials`-
Eintrag, ein weiterer Kalender desselben bereits konfigurierten
Google-Kontos braucht nur einen weiteren `relay.calendars`-Eintrag mit
derselben `google-credentials-id`.

## Persistenz

`StateStore` (das Relay-Mapping Quell-`UID` → Blocker-`UID`/`SEQUENCE`) wird
über Spring Data JPA in einer eingebetteten H2-Datenbank im Dateimodus
gehalten (`STATE_STORE_DATA_DIR`), nicht in-memory — der Zustand muss einen
Prozessneustart überstehen. Pro konfiguriertem Quellkalender existiert eine
eigene `JpaStateStoreAdapter`-Instanz, gescoped auf dessen `id` aus
`relay.calendars`; alle Instanzen teilen sich dieselbe H2-Datenbank und
`RelayStateJpaRepository`.

`CalendarReplicaStore` (die lokale Replik der rohen CalDAV-Ressourcen plus
Sync-Token, die `CalDavCalendarSourceAdapter`s Delta-Sync trägt, siehe
[`docs/features/delta-sync.md`](docs/features/delta-sync.md)) wird ebenfalls
über Spring Data JPA in derselben H2-Datenbank gehalten, über
`JpaCalendarReplicaStoreAdapter` in zwei eigenen Tabellen:
`calendar_replica_resource` (roher `calendar-data`-Inhalt und ETag pro
`href`) und `calendar_sync_token` (ein Sync-Token pro Kalender). Wie
`JpaStateStoreAdapter` existiert pro konfiguriertem Quellkalender eine
eigene `JpaCalendarReplicaStoreAdapter`-Instanz, gescoped auf dessen `id`;
alle Instanzen teilen sich dieselben zwei Repositories
(`CalendarReplicaResourceJpaRepository`, `CalendarSyncTokenJpaRepository`).
Details zum Schema stehen in
[`docs/technical/database.md`](docs/technical/database.md) und
[`docs/technical/caldav.md`](docs/technical/caldav.md).

`GoogleCalendarReplicaStore` (die lokale Replik der rohen Google-Calendar-
Event-JSONs plus Sync-Token, die `GoogleCalendarSourceAdapter`s Delta-Sync
trägt, siehe
[`docs/features/google-calendar-integration.md`](docs/features/google-calendar-integration.md))
folgt demselben Muster in zwei eigenen Tabellen:
`google_calendar_replica_event` (rohes Event-JSON und ETag pro
Google-`eventId`) und `google_calendar_sync_token` (ein Sync-Token pro
Kalender), über `JpaGoogleCalendarReplicaStoreAdapter` und die geteilten
Repositories `GoogleCalendarReplicaResourceJpaRepository`/
`GoogleCalendarSyncTokenJpaRepository`.

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
| [`docs/domain.md`](docs/domain.md) | Fachliches Domänenmodell: Wertobjekte (`SourceEvent`, `BlockerEvent`, `RelayState`, `RelayAction`), Domänendienste und -regeln (z. B. `SEQUENCE`-Invariante, Aufbewahrung abgesagter Zustände, Titellos-Prinzip, Erstellungs-Filter für Bestandsdaten). |
| [`docs/use-cases.md`](docs/use-cases.md) | Use-Case-Katalog: "Poll and Relay Source Calendar" mit Akteur, Ablauf, Fehlerfällen und Ergebnis, beschrieben anhand des tatsächlichen Code-Verhaltens. |
| [`docs/adr/`](docs/adr/) | 11 Architecture Decision Records für nicht offensichtliche Entscheidungen — u. a. eingebettetes H2 pro Kalender (ADR-001), deterministische `blockerUid`-Ableitung aus `sourceUid` (ADR-010, ersetzt ADR-002), Aufbewahrung abgesagter Relay-Zustände (ADR-003), programmatisches Scheduling (ADR-004), Fehlerisolation im Poll-Zyklus (ADR-005), Bean-Definition-Pruning für pro-Kalender-Komponenten (ADR-006), Erstellungs-Filter als reines Neuanlage-Gate (ADR-007), `PendingCreationQueue` als eigener Port statt `StateStore`-Erweiterung (ADR-008), In-Memory-Zähler für `BurstBudget` (ADR-009), enge Klassifikation von "sync-collection nicht unterstützt" (ADR-011). |
| [`docs/technical/`](docs/technical/) | Technische Implementierungsdetails der Adapter: Datenbank, CalDAV (inkl. Delta-Sync), SMTP, Scheduling, Infrastruktur. |
| [`docs/features/relay-orchestration.md`](docs/features/relay-orchestration.md) | Ursprüngliche Vorab-Spezifikation (Forward-Mode) der Poll-and-Relay-Orchestrierung. `docs/use-cases.md` beschreibt das tatsächliche Verhalten und markiert Abweichungen davon explizit. |
| [`docs/features/event-filtering.md`](docs/features/event-filtering.md) | Vorab-Spezifikation (Forward-Mode) der Event-Filterung für das initiale Handling großer Kalenderhistorien (Issue #3) — Erstellungs-Filter, zusammengesetzter `sourceUid` für wiederkehrende Termine, erweiterte Änderungserkennung. Vollständig umgesetzt; `docs/domain.md` und `docs/use-cases.md` fassen das Ergebnis zusammen. |
| [`docs/features/burst-filter-initialization.md`](docs/features/burst-filter-initialization.md) | Vorab-Spezifikation (Forward-Mode) des Burst-Filters für die Erstinitialisierung eines Quellkalenders (Issue #16) — Rückstands-Warteschlange, postfachweites Sendebudget. Vollständig umgesetzt. |
| [`docs/features/delta-sync.md`](docs/features/delta-sync.md) | Vorab-Spezifikation (Forward-Mode) des CalDAV-Delta-Syncs via `sync-collection` (RFC 6578) — Sync-Token, lokale Ressourcen-Replik, Fallback-/Full-Resync-Verhalten. Vollständig umgesetzt. |
| [`docs/features/google-calendar-integration.md`](docs/features/google-calendar-integration.md) | Vorab-Spezifikation (Forward-Mode) von Google Calendar als zweitem, koexistierendem Quellkalender-Typ — Konfigurationsschema, OAuth-Token-Lebenszyklus, `singleEvents=true`-Rekursionsauflösung, dedizierter `GoogleCalendarReplicaStore`-Port. Vollständig umgesetzt. |
| [`docs/features/replica-retirement.md`](docs/features/replica-retirement.md) | Entwurf (Forward-Mode) für ein periodisches Aufräumen alter `CalendarReplicaStore`-/`RelayState`-Einträge. **Nicht umgesetzt** — bewusst nur als fertiges Design geparkt, da die heutige Speicherlast vernachlässigbar ist. |
| [`docs/reference/`](docs/reference/) | Anonymisierte, mit Outlook verifizierte iMIP-Referenz-Mails (`.eml`), die die strukturellen Anforderungen an die generierten Nachrichten belegen. |

### Architekturüberblick

Der Service folgt Hexagonal Architecture (siehe [`CLAUDE.md`](CLAUDE.md)):
Die Anwendungsschicht (`core/app`) orchestriert pro konfiguriertem
Quellkalender einen Poll-Zyklus über mehrere ausgehende Ports —
`CalendarSource` (CalDAV-Lesezugriff, intern per Delta-Sync gegen
`CalendarReplicaStore` optimiert), `StateStore` (Relay-Bookkeeping),
`PendingCreationQueue` (Rückstands-Warteschlange der Erstinitialisierung),
`BurstBudget` (postfachweites Sendebudget) und `BlockerSink`
(iMIP-Versand per SMTP) — während der reine Domänenkern (`core/domain`) die
Diff-Entscheidung (`RelayDiffPlanner`) und das iMIP/ICS-Rendering
(`ImipCalendarRenderer`) unabhängig von jedem Framework kapselt. Ein
programmatischer Scheduler (`adapters/inbound/scheduling`) treibt jede
Use-Case-Instanz an. Details zum fachlichen Modell stehen in
`docs/domain.md`, zu den Use Cases in `docs/use-cases.md`, zu den
Implementierungsentscheidungen in `docs/adr/` und `docs/technical/`.

## Lokal starten

1. Java 25 und Maven installieren (siehe Voraussetzungen).
2. `.env.example` nach `.env` kopieren und mit echten SMTP- sowie
   CalDAV-Zugangsdaten befüllen (siehe [Konfiguration](#konfiguration)).
3. `mvn clean install` ausführen, um Build und Tests einmal vollständig
   laufen zu lassen.
4. `mvn spring-boot:run` starten. Für einen ersten Testlauf ohne echten
   Quellkalender genügt das bereits — `relay.calendars` ist in
   `src/main/resources/application.yml` bewusst leer gelassen, die
   Anwendung startet dann sauber ohne konfigurierte Kalender.
5. Log-Ausgabe beobachten: `PollAndRelaySchedulerAdapter` protokolliert
   nach jedem Poll-Zyklus (`relay.poll-interval`) das Ergebnis pro
   konfiguriertem Quellkalender.

### `relay.calendars` für einen echten lokalen Lauf befüllen

Schritt 4 oben startet absichtlich ohne Quellkalender — `relay.calendars`
selbst wird dabei **nicht** aus `.env` befüllt, `.env` deckt nur die darin
referenzierten Umgebungsvariablen ab (`CALDAV_PERSONAL_USERNAME` etc.), nicht
die Kalenderliste als solche. Um tatsächlich einen Quellkalender zu pollen,
muss `relay.calendars` selbst gesetzt werden. Spring Boots automatische
externe Konfiguration lädt aus `./config/` ausschließlich Dateien, deren Name
zu `application(-*).yml` passt — eine Datei namens `relay-calendars.yml` wird
dort **nicht** automatisch eingelesen; das ist kein Docker-spezifisches
Detail, sondern gilt für jeden Startweg. Zwei gleichwertige Optionen:

- **a) Wie in Docker, über `SPRING_CONFIG_ADDITIONAL_LOCATION`:**
  `config/relay-calendars.yml.example` nach `config/relay-calendars.yml`
  kopieren (git-ignored) und befüllen, dann beim Start explizit als
  zusätzliche Konfigurationsquelle einbinden, z. B.
  `SPRING_CONFIG_ADDITIONAL_LOCATION=file:./config/relay-calendars.yml mvn spring-boot:run`
  (Bash/Git-Bash) bzw. `$env:SPRING_CONFIG_ADDITIONAL_LOCATION =
  "file:./config/relay-calendars.yml"; mvn spring-boot:run` (PowerShell).
  Identischer Mechanismus wie im Docker-Betrieb (siehe unten) — dieselbe
  Beispieldatei funktioniert für beide Wege.
- **b) Direkt in `application.yml`:** den auskommentierten
  `relay.calendars`-Beispielblock in `src/main/resources/application.yml`
  einkommentieren und befüllen. Einfacher für einen schnellen lokalen Test,
  aber `application.yml` ist eine versionierte Datei — echte
  Zugangsdaten-Platzhalter (`${CALDAV_PERSONAL_USERNAME}` usw.) sind
  unkritisch, ein versehentliches Einchecken darf aber trotzdem nicht
  passieren.

## Bauen & Testen

```bash
mvn clean install          # Build + alle Tests
mvn test                   # Nur Tests
mvn spring-boot:run         # Lokal starten
```

## Docker

Vor dem ersten Start:

1. `.env.example` nach `.env` kopieren und echte Werte eintragen (SMTP,
   CalDAV-Zugangsdaten, Relay-Identität pro Kalender).
2. `config/relay-calendars.yml.example` nach `config/relay-calendars.yml`
   kopieren und die tatsächliche(n) Kalenderliste(n) eintragen (Datei muss
   existieren, bevor `docker compose up` läuft — sonst legt Docker an ihrer
   Stelle ein leeres Verzeichnis an). Beide Dateien sind `.gitignore`t.
3. `data/`-Verzeichnis für die H2-Datenbank anlegen und dem Container-User
   gehören lassen (feste UID/GID `10001`, siehe `Dockerfile`):
   ```bash
   mkdir -p data && sudo chown 10001:10001 data
   ```

```bash
docker compose up --build
```

`docker-compose.yml` reicht `.env` unverändert als Environment durch und
mountet `config/relay-calendars.yml` read-only nach
`/app/config/relay-calendars.yml`, per `SPRING_CONFIG_ADDITIONAL_LOCATION`
eingebunden (siehe "Quellkalender (`relay.calendars`)" oben).

Der Build löst `ms.rohde:hexagonal-arch-*` (`1.0.0-SNAPSHOT`) über das
interne Maven-Repository auf (`settings.xml`, siehe
`docs/technical/infrastructure.md`), nicht aus dem lokalen `.m2`-Cache —
ein containerisierter Build funktioniert daher auch auf einer frischen
Maschine, solange diese das interne Repository erreichen kann.

### Deployment auf einem anderen Docker-Host (ohne Build-Toolchain dort)

Für ein Zielsystem, das gar keinen Zugriff auf das interne Maven-Repository
oder die Build-Toolchain haben soll (z. B. ein Produktiv-Host), wird das
Image hier gebaut und als Tarball exportiert:

```bash
./build-and-export-image.sh
```

Erzeugt `business-calendar-relay-image.tar` (immer ein sauberer
`--no-cache`-Build, kein Risiko einer veralteten Schicht im Export). Auf
das Zielsystem müssen kopiert werden: die Tarball-Datei,
`docker-compose.deploy.yml`, sowie die befüllten `.env` und
`config/relay-calendars.yml` (siehe oben). Dort dann:

```bash
docker load -i business-calendar-relay-image.tar
docker compose -f docker-compose.deploy.yml up -d
```

`docker-compose.deploy.yml` unterscheidet sich von `docker-compose.yml`
nur durch das Fehlen der `build:`-Sektion — funktional identische
Umgebungsvariablen, Volumes und Healthcheck. Erneutes Ausführen von
`build-and-export-image.sh` nach Code-Änderungen erzeugt einen aktuellen
Tarball; `docker-compose.yml`s explizites `image: business-calendar-relay:latest`
sorgt dafür, dass Build und Export denselben, vorhersagbaren Image-Namen
verwenden.

### Datenbank-Datei / Zustand zwischen Umgebungen synchronisieren

Die eingebettete H2-Datenbank (`StateStore`, `CalendarReplicaStore`,
`PendingCreationQueue` — siehe `docs/technical/database.md`) liegt als
Bind-Mount unter `./data/` (nicht als Docker-named-Volume), also als ganz
normale Datei(en) auf dem Host — standardmäßig `data/relay-state.mv.db`
(H2s Dateiendung, siehe `spring.datasource.url` in `application.yml`).
Damit lässt sich der Zustand direkt zwischen einer echten Produktivumgebung
und einer lokalen Testumgebung kopieren (`scp`/`rsync`), z. B. um beim
lokalen Testen mit demselben, bereits bekannten Terminbestand zu starten,
statt bei jedem Testlauf gegen eine leere Datenbank zu pollen — das ist
genau der Fall, der sonst zu "sieht alles neu aus"-Mail-Dopplungen führt.
Container vor dem Kopieren stoppen (`docker compose stop`), damit H2 keine
offenen Schreiboperationen hat.
