# Datenbank / StateStore

`StateStore` (`ports/outbound/StateStore.java`) ist der Port für das Relay-Mapping
Quell-`UID` → Blocker-`UID`/`SEQUENCE`. Implementiert wird er von
`JpaStateStoreAdapter` (`adapters/outbound/persistence/JpaStateStoreAdapter.java`)
über Spring Data JPA gegen eine eingebettete, dateibasierte H2-Datenbank.

## Warum dateibasiertes H2 statt externer DB

Der Zustand muss einen Prozessneustart überstehen — eine In-Memory-DB scheidet
damit aus. Gleichzeitig ist der Service ein Single-Process-Deployment ohne
Bedarf an nebenläufigem Zugriff mehrerer Prozessinstanzen auf denselben
Zustand, und die Datenmenge (ein Zeilensatz pro Quell-Event, pro Kalender) ist
klein. Eine eingebettete, dateibasierte H2-Instanz deckt das ohne zusätzliche
Infrastruktur (kein separater DB-Container, kein Netzwerkpfad, kein
Credential-Management für eine externe DB) ab. Der einzige operative
Anspruch: Das Datenverzeichnis muss neustartfest sein — in Docker als Volume
gemountet (siehe `infrastructure.md`).

## `STATE_STORE_DATA_DIR` → JDBC-URL

`application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:file:${STATE_STORE_DATA_DIR:./data}/relay-state
    driver-class-name: org.h2.Driver
    username: sa
    password:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: update
```

Die Umgebungsvariable `STATE_STORE_DATA_DIR` (Default `./data`) wird direkt in
den H2-Dateipfad interpoliert: die DB-Dateien landen unter
`${STATE_STORE_DATA_DIR}/relay-state.mv.db`. In `docker-compose.yml` wird
`STATE_STORE_DATA_DIR=/app/data` gesetzt und dieses Verzeichnis über das
Named Volume `relay-state-data` gemountet, damit die Datei einen
Container-Neustart übersteht.

`hibernate.ddl-auto: update` lässt Hibernate das Schema aus den
`@Entity`-Klassen ableiten und bei Bedarf ergänzen — es gibt kein Flyway/
Liquibase in diesem Projekt. Für ein Projekt dieser Größe (eine einzige
Entity) ist das bewusst gewählt statt eines Migrationswerkzeugs; bei
wachsender Schema-Komplexität wäre das ein Kandidat für eine spätere
Ablösung.

## Schema: `relay_state`

Einzige Tabelle, abgebildet über `RelayStateEntity`
(`adapters/outbound/persistence/RelayStateEntity.java`):

| Spalte | Typ (Java) | Nullable | Beschreibung |
|---|---|---|---|
| `source_calendar_id` | `String` | nein, unveränderlich | Teil des zusammengesetzten Primärschlüssels; entspricht `relay.calendars[].id` |
| `source_uid` | `String` | nein, unveränderlich | Teil des zusammengesetzten Primärschlüssels; `UID` des Quell-`VEVENT` |
| `blocker_uid` | `String` | nein | `UID` des iMIP-Blockers in Outlook, stabil über den gesamten Lebenszyklus (Create → Updates → Cancel) |
| `sequence_number` | `long` | nein | Letzter gesendeter `SEQUENCE`-Wert für `blocker_uid`; einzige Quelle der Wahrheit für den nächsten `SEQUENCE`-Wert |
| `last_known_start` | `String` (konvertiert aus `ZonedDateTime`) | nein | Letzter bekannter `DTSTART` des Quell-Events, ISO-8601 mit vollständiger Zeitzone |
| `last_known_end` | `String` (konvertiert aus `ZonedDateTime`) | nein | Letzter bekannter `DTEND` des Quell-Events, analog |
| `last_known_all_day` | `boolean` | nein | Letzter bekannter Ganztägig-Status des Quell-Events (seit Event-Filtering-Feature, Issue #3) |
| `last_known_busy` | `boolean` | nein | Letzter bekannter Beschäftigt-Status (`TRANSP` ≠ `TRANSPARENT`) des Quell-Events (seit Issue #3) |
| `last_known_cancelled` | `boolean` | nein | Letzter bekannter `STATUS:CANCELLED`-Status des Quell-Events (seit Issue #3) |
| `active` | `boolean` | nein | `true` solange das Quell-Event existiert und nicht storniert ist; `false` nach gesendetem `CANCEL` |

Die drei `last_known_*`-Boolean-Spalten wurden mit dem Event-Filtering-Feature
(Issue #3, `docs/features/event-filtering.md`) ergänzt: `RelayDiffPlanner`s
Änderungserkennung löst seitdem nicht mehr nur bei geändertem `start`/`end`
ein Update aus, sondern auch bei geändertem Ganztägig-/Beschäftigt-/
Storniert-Status. `ddl-auto: update` hat die Spalten automatisch ergänzt,
keine manuelle Migration nötig.

Primärschlüssel: zusammengesetzt aus `(source_calendar_id, source_uid)`, über
`@IdClass(RelayStateEntityId.class)` abgebildet
(`RelayStateEntityId.java`). Zusätzlich existiert ein expliziter
`UniqueConstraint` auf denselben zwei Spalten (redundant zum PK, aber als
Absicherung im generierten DDL dokumentiert).

Zeilen werden **nie gelöscht**: Ein storniertes Event bleibt mit `active =
false` erhalten. Taucht dasselbe `source_uid` später wieder auf, wird
dieselbe `blocker_uid` und der fortlaufende `sequence`-Zähler
weiterverwendet, statt einen doppelten Blocker anzulegen (siehe JavaDoc auf
`RelayState`, `core/domain/RelayState.java`).

### `ZonedDateTimeStringConverter`

`last_known_start`/`last_known_end` werden nicht über Hibernates
Standard-Mapping für `ZonedDateTime` (das über einen SQL-Timestamp/Offset-Typ
läuft) persistiert, sondern über den expliziten
`@Converter`-`ZonedDateTimeStringConverter`
(`adapters/outbound/persistence/ZonedDateTimeStringConverter.java`) als
String im vollen ISO-8601-Format inklusive benannter `ZoneId` (z. B.
`Europe/Berlin`), nicht nur als numerischer UTC-Offset. Grund: Hibernates
Standardmapping normalisiert eine benannte Zone stillschweigend auf einen
festen Offset, was `RelayState`s Änderungserkennung bricht — die auf
`ZonedDateTime.equals(Object)` beruht, was dieselbe `ZoneId`-Instanz
vergleicht, nicht nur denselben Zeitpunkt.

## Schema: `pending_creation`

Zweite Tabelle, seit dem Burst-Filter-Feature für die Erstinitialisierung eines
Kalenders (Issue #16, `docs/features/burst-filter-initialization.md`),
abgebildet über `PendingCreationEntity`
(`adapters/outbound/persistence/PendingCreationEntity.java`) — strukturell
ein direktes Geschwister von `RelayStateEntity`, aber ein eigener, dedizierter
Port (`PendingCreationQueue`) und eine eigene Tabelle statt einer Erweiterung
von `relay_state`, weil `RelayState`s Invarianten fachlich nicht zum Zustand
"existiert noch gar nicht als Blocker, wartet nur auf seinen Sendezeitpunkt"
passen:

| Spalte | Typ (Java) | Nullable | Beschreibung |
|---|---|---|---|
| `source_calendar_id` | `String` | nein, unveränderlich | Teil des zusammengesetzten Primärschlüssels; entspricht `relay.calendars[].id` |
| `source_uid` | `String` | nein, unveränderlich | Teil des zusammengesetzten Primärschlüssels; `UID` des Quell-`VEVENT` |
| `blocker_uid` | `String` | nein | bereits deterministisch abgeleitete `blockerUid` dieser künftigen Erstanlage |
| `start` | `String` (konvertiert aus `ZonedDateTime`) | nein | `DTSTART` des Quell-Events zum Capture-Zeitpunkt, über denselben `ZonedDateTimeStringConverter` wie `last_known_start` bei `relay_state`, aus Konsistenzgründen |
| `end` | `String` (konvertiert aus `ZonedDateTime`) | nein | analog, `DTEND` zum Capture-Zeitpunkt |
| `all_day` | `boolean` | nein | Ganztägig-Status des auslösenden `SourceEvent` zum Capture-Zeitpunkt |
| `busy` | `boolean` | nein | Beschäftigt-Status, analog |
| `cancelled` | `boolean` | nein | Storniert-Status, analog |

Bewusst **kein** `sequence`- oder `active`-Feld — beides ist für diese
Tabelle bedeutungslos: `sequence` ist für eine Erstanlage strukturell immer
`0`, und "aktiv" hat hier keine eigene Bedeutung, da eine Zeile entweder in
der Tabelle existiert (dann ausstehend) oder nicht mehr (dann versendet oder
als veraltet verworfen). Primärschlüssel zusammengesetzt aus
`(source_calendar_id, source_uid)`, über `@IdClass(PendingCreationEntityId.class)`
abgebildet, genau wie bei `relay_state`. Anders als `relay_state` werden
Zeilen hier tatsächlich gelöscht, sobald sie gedraint oder als veraltet
verworfen wurden — die Tabelle ist eine echte Warteschlange, keine
dauerhafte Historie. `ddl-auto: update` legt die Tabelle automatisch an,
keine manuelle Migration nötig.

`start` und `end` sind in H2 (wie in vielen SQL-Dialekten) reservierte
Schlüsselwörter -- `end` insbesondere kollidiert mit H2s eigener
`CASE ... END`-Syntax und lässt die generierte `CREATE TABLE`-DDL sonst mit
einem Syntaxfehler scheitern. `PendingCreationEntity` mappt beide Spalten
deshalb über Hibernates Backtick-Escaping (`@Column(name = "`start`")` bzw.
`` "`end`" ``), was Hibernate automatisch in die dialektspezifische
Quotierung übersetzt (bei H2 doppelte Anführungszeichen), ohne den
eigentlichen Spaltennamen zu verändern.

`JpaPendingCreationQueueAdapter implements PendingCreationQueue`
(`adapters/outbound/persistence/JpaPendingCreationQueueAdapter.java`) folgt
strukturell exakt `JpaStateStoreAdapter`s Aufbau: Konstruktor nimmt ein
geteiltes `PendingCreationJpaRepository` (`PendingCreationJpaRepository.java`)
und die pro-Kalender-`sourceCalendarId` entgegen; jede Methode filtert
explizit über diese ID.
`findAllBySourceCalendarIdOrderByStartAsc(String)` liefert die für
`loadAllOrderedByStart()` geforderte, aufsteigende Sortierung nach `start`
direkt über die Query-Methode, ohne dass der Adapter selbst sortieren muss.
`saveAll(...)` ist reines Insert (kein Upsert/Merge — laut Spec kein
vorgesehener Anwendungsfall für eine nicht-leere Warteschlange desselben
Kalenders), und `remove(sourceUid)` ist idempotent: das Entfernen einer
nicht (mehr) vorhandenen Zeile ist kein Fehler. Genau wie
`JpaStateStoreAdapter` ist auch dieser Adapter **kein** auto-gescannter
Spring-Singleton-Bean (Konstruktor braucht die pro-Kalender-
`sourceCalendarId`) — `RelayWiringConfiguration` konstruiert eine Instanz
pro Kalender von Hand, und `PerCalendarComponentBeanDefinitionPruner`
entfernt die dafür überflüssige, nicht konstruierbare
`@ArchComponentScan`-Bean-Definition, exakt wie bereits für
`JpaStateStoreAdapter` beschrieben (siehe unten und
[`scheduling.md`](scheduling.md#multi-kalender-verdrahtung)).

## Per-Kalender-Scoping: eine `JpaStateStoreAdapter`-Instanz pro Kalender

Pro konfiguriertem Quellkalender existiert eine eigene
`JpaStateStoreAdapter`-Instanz, deren Konstruktor eine
`RelayStateJpaRepository`-Instanz und die `sourceCalendarId` (den `id`-Wert
aus `relay.calendars`) entgegennimmt:

```java
public JpaStateStoreAdapter(RelayStateJpaRepository repository, String sourceCalendarId)
```

Alle Instanzen teilen sich **dasselbe** `RelayStateJpaRepository` (Spring
Data JPA Repository-Interface, `adapters/outbound/persistence/
RelayStateJpaRepository.java`) und damit dieselbe H2-Datenbank. Jede Instanz
filtert aber jeden Aufruf explizit über ihre eigene `sourceCalendarId`
(`findAllBySourceCalendarId`, `findBySourceCalendarIdAndSourceUid`), sodass
eine Instanz nie Zeilen eines anderen Kalenders sieht oder verändert — die
Isolation liegt vollständig in der Query, nicht in getrennten Tabellen oder
Datenbanken.

`JpaStateStoreAdapter` ist **kein** auto-gescannter Spring-Singleton-Bean,
obwohl die Klasse mit `@InfrastructureServiceAdapter` annotiert ist (was
`@ArchComponentScan` grundsätzlich als Bean registrieren würde). Der
Konstruktor erwartet eine `String`-`sourceCalendarId`, für die es keinen
passenden Spring-Bean gibt — eine automatisch registrierte Bean-Definition
würde beim Context-Refresh mit einer `UnsatisfiedDependencyException`
fehlschlagen. Stattdessen instanziiert `RelayWiringConfiguration`
(`config/RelayWiringConfiguration.java`) für jeden Eintrag in
`relay.calendars` eine eigene Instanz von Hand:

```java
var stateStore = new JpaStateStoreAdapter(relayStateJpaRepository, calendar.id());
```

und die dazu automatisch von `@ArchComponentScan` registrierte, aber nicht
konstruierbare Bean-Definition wird von
`PerCalendarComponentBeanDefinitionPruner` vor der Singleton-Vorinstanziierung
wieder entfernt. Details zu diesem Mechanismus stehen in
[`scheduling.md`](scheduling.md#multi-kalender-verdrahtung).

## Bekannte Trade-offs

- Kein Migrationswerkzeug (Flyway/Liquibase) — `ddl-auto: update` reicht für
  das aktuell einzige Entity, ist aber kein Ersatz für versionierte
  Migrationen, sobald das Schema wächst oder produktive Daten
  Breaking-Changes überstehen müssen.
- Die H2-Datei ist an genau einen Prozess gebunden (Filesystem-Lock); mehrere
  gleichzeitig laufende Instanzen des Service gegen dasselbe
  `STATE_STORE_DATA_DIR` sind nicht unterstützt.
