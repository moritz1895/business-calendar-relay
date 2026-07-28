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
