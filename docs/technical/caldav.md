# CalDAV-Adapter (`CalendarSource`)

`CalDavCalendarSourceAdapter`
(`adapters/outbound/caldav/CalDavCalendarSourceAdapter.java`) implementiert
den Port `CalendarSource` (`ports/outbound/CalendarSource.java`) und liefert
bei jedem `readEvents()`-Aufruf den vollständigen aktuellen Bestand an
`VEVENT`s einer privaten CalDAV-Kalender-Collection als `SourceEvent`-Liste.
Der öffentliche Vertrag ("immer ein vollständiger Snapshot, nie ein Delta")
ist unverändert; **wie** der Adapter intern zu diesem Snapshot kommt, hängt
von der Konfiguration und der Server-Fähigkeit ab — siehe
`docs/features/delta-sync.md` für die vollständige Herleitung.

## Zwei Beschaffungswege

- **`sync-collection` REPORT (RFC 6578), Standardfall.** Aktiv, sobald
  `deltaSyncEnabled` für diesen Kalender `true` ist (Default) und der Server
  nicht bereits als nicht unterstützend erkannt wurde. Der Adapter hält dafür
  pro Kalender eine lokale Replik aller rohen CalDAV-Ressourcen
  (`CalendarReplicaStore`) und aktualisiert diese anhand der vom Server
  gemeldeten Deltas, statt bei jedem Poll die komplette Collection erneut
  anzufordern. Der vollständige `SourceEvent`-Bestand wird bei jedem
  `readEvents()`-Aufruf aus der **gesamten** lokalen Replik neu berechnet
  (nicht nur aus dem zuletzt gelieferten Delta) — siehe "Antwortverarbeitung"
  unten.
- **`calendar-query` REPORT (RFC 4791), Legacy/Fallback.** Immer eine
  vollständige Abfrage ohne Zeitraum-Einschränkung. Aktiv, wenn
  `deltaSyncEnabled == false`, oder sobald der Server einmalig als
  `sync-collection`-inkompatibel erkannt wurde (siehe "Erkennung fehlender
  Server-Unterstützung" unten).

Welcher Weg jeweils genutzt wird, ist für `CalendarSource`-Aufrufer
(`PollAndRelaySourceCalendarService`) unsichtbar: Beide münden in dieselbe,
durch dieses Feature unveränderte `expandAll`/`expandSeries`-Pipeline (siehe
"Mapping `VEVENT` → `SourceEvent`" unten).

## Anfrage: `sync-collection` REPORT (RFC 6578)

Der XML-Body ist ein Template, das bei jedem Aufruf mit dem aktuell
persistierten Sync-Token gefüllt wird (leerer Token beim initialen Sync):

```xml
<?xml version="1.0" encoding="utf-8" ?>
<D:sync-collection xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:sync-token>%s</D:sync-token>
  <D:sync-level>1</D:sync-level>
  <D:prop>
    <D:getetag/>
    <C:calendar-data/>
  </D:prop>
</D:sync-collection>
```

Der Token-Wert wird vor dem Einsetzen XML-escaped (`escapeXml`). Der
HTTP-Request nutzt dieselbe `executeReport(...)`-Methode und damit dieselben
Header wie `calendar-query`: Methode `REPORT`, `Depth: 1` (zusätzlich zu
`<D:sync-level>1</D:sync-level>` gesetzt — manche Server prüfen beides),
`Content-Type: application/xml; charset=utf-8`, `Authorization: Basic
<base64(username:password)>`. Anders als `calendar-query` kennt
`sync-collection` laut RFC 6578 kein `<C:filter>`-Element; die Antwort kann
grundsätzlich jede Ressourcenart der Collection enthalten. Das ist
folgenlos, da `parseVEvents(...)` ohnehin nur `VEVENT`-Komponenten extrahiert
und für eine Ressource ohne `VEVENT` einfach eine leere Liste liefert.

### Initialer vs. inkrementeller Sync

- **Kein Token persistiert** (`CalendarReplicaStore.loadSyncToken() == null`,
  `performInitialSync()`): Anfrage mit leerem Token. Der Server liefert
  laut RFC 6578 §3.2 die vollständige aktuelle Collection plus einen neuen
  Sync-Token. Das Ergebnis ersetzt die lokale Replik vollständig
  (`CalendarReplicaStore.resetTo(newSyncToken, changedResources)`). Tritt
  genau einmal pro Kalender ein: beim ersten Poll nach Rollout dieses
  Features (unabhängig davon, ob der Kalender vorher schon lange über
  `calendar-query` lief — ein bestehender `RelayState`-Bestand bleibt davon
  unberührt) oder nach einem erzwungenen Full-Resync.
- **Token vorhanden** (`performIncrementalSync(token)`): Antwort auf `207
  Multi-Status` wird als Delta angewendet
  (`CalendarReplicaStore.applyDelta(newSyncToken, changedResources,
  removedHrefs)`) — Upsert der geänderten Ressourcen, Entfernen der
  gelöschten `href`s, Vorrücken des Tokens, alles in einer
  `@Transactional`-Operation von `JpaCalendarReplicaStoreAdapter`.

### Antwortverarbeitung: `parseSyncCollectionResponse(...)`

Wie bei `calendar-query` ein `207 Multi-Status` mit `<D:response>`-Elementen
pro Ressource, zusätzlich mit einem Top-Level-`<D:sync-token>`-Element
(direktes Geschwister der `<D:response>`-Elemente unter `<D:multistatus>`)
mit dem neuen Token für den nächsten Abgleich — fehlt dieses Element, wirft
der Adapter eine `CalDavCalendarSourceException`. Pro `<D:response>`:

- **Neu oder geändert:** ein `<D:propstat>` mit Erfolgsstatus (dieselbe
  `isSuccessStatus(...)`-Prüfung wie bei `calendar-query`) enthält
  `<D:getetag>` und `<C:calendar-data>`; wird als `CachedCalendarResource
  (href, etag, rawCalendarData)` gesammelt.
- **Entfernt:** ein direktes `<D:status>`-Kindelement von `<D:response>`
  (kein `<D:propstat>`) mit Statuszeile `404`; der `href` dieses
  `<D:response>`-Elements landet in `removedHrefs`.
- Jedes andere Muster wird übersprungen und lässt die lokale Replik für
  diesen `href` unverändert — dieselbe Nachsichtigkeit wie bei
  `calendar-query`s `propstat`-Behandlung.

### `CalendarReplicaStore`: rohe Replik statt expandierter Vorkommen

`CalendarReplicaStore` (`ports/outbound/CalendarReplicaStore.java`, Adapter
`JpaCalendarReplicaStoreAdapter`) speichert **ausschließlich** rohe,
unexpandierte `calendar-data`-Blobs, indiziert über `href` (die
WebDAV-Ressourcenidentität, nicht die `UID` — `sync-collection` meldet
Deltas über `href`). Zwei Tabellen, beide mit `hibernate.ddl-auto: update`
automatisch angelegt:

| Tabelle | Primärschlüssel | Spalten | Zweck |
|---|---|---|---|
| `calendar_replica_resource` | `(source_calendar_id, href)` | `etag`, `raw_calendar_data` (`@Lob`) | letzter bekannter Rohinhalt jeder CalDAV-Ressource |
| `calendar_sync_token` | `source_calendar_id` | `sync_token` (nullable) | ein Token pro Kalender; `null` = noch kein initialer Sync abgeschlossen |

`etag` wird gespeichert, aber vom Adapter nie verglichen — rein
diagnostisch, da der Server über die Delta-Antwort selbst bereits die
alleinige autoritative Quelle für "geändert" ist. Bei jedem
`readEvents()`-Aufruf liest der Adapter über
`calendarReplicaStore.loadAllResources()` die **gesamte** aktuelle Replik
(nicht nur das zuletzt gelieferte Delta), parst jeden Blob über das
bestehende `parseVEvents(...)` und übergibt die komplette `VEvent`-Menge an
die unveränderte `expandAll(...)`-Pipeline. Das ist notwendig, weil das
`recurring-event-horizon`-Zeitfenster relativ zu `now` mit jedem Poll
weiterrückt: Eine wiederkehrende Serie, deren zugrunde liegende Ressource
sich nie ändert, muss trotzdem bei jedem Zyklus neue Vorkommen offenbaren
können, sobald das Fenster weiter fortschreitet. Eine Cache-Strategie, die
stattdessen bereits expandierte Vorkommen zwischenspeichert, würde eine
unveränderte, aber unbegrenzt wiederkehrende Serie dauerhaft auf dem
Vorkommen-Stand des letzten tatsächlichen Deltas einfrieren.

Kein Pruning/keine Größenbegrenzung von `calendar_replica_resource` — die
Tabelle wächst mit der Anzahl je gemeldeter `href`s und schrumpft nur bei
einer tatsächlichen Löschmeldung des Servers.

### Erzwungener Full-Resync bei ungültigem Sync-Token

`isInvalidSyncTokenResponse(...)` erkennt zwei Fälle als "Token nicht mehr
gültig": `507 Insufficient Storage`, oder `403 Forbidden` mit dem
Precondition-Element `<D:valid-sync-token/>` innerhalb eines
`<D:error>`-Bodys (`containsValidSyncTokenPrecondition(...)`, geprüft über
denselben gehärteten XML-Parser wie die übrige Antwortverarbeitung). Beide
lösen `performInitialSync()` aus: erneute Anfrage mit leerem Token,
vollständiger Replik-Reset über `resetTo(...)`. Kein Datenverlust — im
schlimmsten Fall kostet dieser eine Zyklus dieselbe Serverlast wie ein
heutiger `calendar-query`-Poll. Ein bereits bestehender `RelayState`-Bestand
ist davon unberührt.

### Erkennung fehlender Server-Unterstützung

`isDefinitelyUnsupportedResponse(...)` klassifiziert eine
`sync-collection`-Antwort, die weder `207` noch als ungültiger Token
erkennbar ist, als "Server unterstützt `sync-collection` für diese
Collection nicht" — **nur** für die folgenden konkreten Statuscodes:

- `501 Not Implemented`
- `415 Unsupported Media Type`
- `403 Forbidden`, dessen Body **nicht** die `<D:valid-sync-token/>`-
  Precondition trägt (z. B. eine `<D:supported-report/>`-Precondition nach
  RFC 3253, oder kein erkennbarer Precondition-Body)

Trifft einer dieser Fälle zu, wirft `unexpectedSyncCollectionResponse(...)`
intern eine `SyncCollectionUnsupportedException`, die
`readAllVEventsViaDeltaSyncOrFallback()` fängt: das Instanzfeld
`deltaSyncPermanentlyDisabled` wird dauerhaft (nur im Prozessspeicher, nicht
persistiert) auf `true` gesetzt, einmalig eine `WARN`-Log-Zeile mit
Statuscode und Response-Body geschrieben, und für den aktuellen sowie jeden
weiteren `readEvents()`-Aufruf dieser Instanz direkt die Legacy-
`calendar-query`-Anfrage verwendet — `CalendarReplicaStore` wird ab dann
nicht mehr angefasst. Ein Prozessneustart setzt das Flag zurück und
versucht `sync-collection` erneut.

**Diese Klassifikation ist absichtlich eng gefasst** — jeder andere,
nicht erkannte Statuscode (z. B. ein transienter `503 Service Unavailable`)
löst **keinen** dauerhaften Fallback aus, sondern lediglich eine gewöhnliche
`CalDavCalendarSourceException`, die nur den aktuellen Poll-Zyklus scheitern
lässt: Der nächste Zyklus versucht `sync-collection` erneut mit demselben,
weiterhin gültigen Token. Eine breitere Klassifikation (jede unbekannte
Statuszeile als "nicht unterstützt" werten) hätte einen rein transienten
Fehler fälschlich in einen dauerhaften, erst durch Prozessneustart
behebbaren Fallback auf `calendar-query` verwandelt — dieser Unterschied
wurde nachträglich korrigiert, nachdem eine erste, breitere Fassung genau
dieses Regressionsrisiko trug.

## Legacy: `calendar-query` REPORT (RFC 4791)

Verwendet, wenn `deltaSyncEnabled == false` ist oder
`deltaSyncPermanentlyDisabled` für diese Instanz gesetzt wurde. Immer eine
vollständige Abfrage ohne Zeitraum-Einschränkung und ohne Sync-Token. Der
XML-Body ist fest verdrahtet:

```xml
<?xml version="1.0" encoding="utf-8" ?>
<C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop>
    <D:getetag/>
    <C:calendar-data/>
  </D:prop>
  <C:filter>
    <C:comp-filter name="VCALENDAR">
      <C:comp-filter name="VEVENT"/>
    </C:comp-filter>
  </C:filter>
</C:calendar-query>
```

Der HTTP-Request:

- Methode `REPORT` gegen die konfigurierte `caldav-url` (Collection-URL)
- Header `Depth: 1` (durchsucht die Collection selbst, nicht rekursiv tiefer)
- Header `Content-Type: application/xml; charset=utf-8`
- Header `Authorization: Basic <base64(username:password)>`

Erwartete Antwort: HTTP `207 Multi-Status`. Jeder andere Statuscode wirft eine
`CalDavCalendarSourceException`.

## Auth: Basic, kein Challenge-Response

Die Basic-Credentials werden auf **jedem** Request direkt mitgeschickt,
statt sich auf `java.net.Authenticator`-Challenge-Response (erst `401`,
dann Retry mit Credentials) zu verlassen — laut Klassen-JavaDoc, weil
CalDAV-Server keinen zuverlässigen sauberen `401`-Challenge vor der Annahme
von Basic-Auth auf einem `REPORT` ausgeben. Gilt gleichermaßen für
`sync-collection`- und `calendar-query`-Requests, beide laufen über
dieselbe `executeReport(...)`-Methode.

## HTTP-Client

Verwendet wird `java.net.http.HttpClient` (JDK-Bordmittel, kein zusätzlicher
HTTP-Client als Dependency). Eine einzige `HttpClient`-Instanz wird als
Spring-Bean (`RelayWiringConfiguration#relayCalDavHttpClient`) angelegt und
von **allen** konfigurierten Kalender-Adapterinstanzen gemeinsam genutzt.

## Antwortverarbeitung (gemeinsame Bausteine)

1. **XML-Parsing der Multistatus-Antwort**: über einen bewusst gehärteten
   `DocumentBuilder` (`newSecureDocumentBuilder()`) — DOCTYPE-Deklarationen
   sind verboten, externe General-/Parameter-Entities sind deaktiviert,
   XInclude ist aus, Entity-Referenzen werden nicht expandiert. Das schützt
   gegen XXE-artige Angriffe über eine kompromittierte oder böswillige
   CalDAV-Antwort. Derselbe gehärtete Parser wird sowohl für
   `calendar-query`- als auch `sync-collection`-Antworten (inklusive der
   Precondition-Body-Prüfung auf `<D:valid-sync-token/>`) verwendet.
2. **Extraktion der `calendar-data`-Blobs**: Für jedes `<D:response>` werden
   dessen `<D:propstat>`-Elemente durchsucht; nur `propstat`-Blöcke mit
   Erfolgsstatus (HTTP-Statuszeile beginnt mit `2`) werden berücksichtigt.
   Aus jedem erfolgreichen `propstat` wird der Textinhalt von
   `<C:calendar-data>` als roher ICS-Blob gesammelt (bei `calendar-query`
   direkt zu `VEvent`s geparst; bei `sync-collection` zunächst über
   `CalendarReplicaStore` zwischengespeichert, siehe oben).
3. **ICS-Parsing pro Blob**: jeder Blob wird über `ical4j`
   (`net.fortuna.ical4j`, `CalendarBuilder`) geparst; alle enthaltenen
   `VEVENT`-Komponenten werden extrahiert.

## Mapping `VEVENT` → `SourceEvent`

Alle über sämtliche `calendar-data`-Blobs (aus der aktuellen
`calendar-query`-Antwort oder aus der vollständigen `CalendarReplicaStore`-
Replik, siehe oben — die Pipeline ab hier ist für beide Beschaffungswege
identisch) geparsten `VEVENT`s werden zuerst per `UID` gruppiert
(`expandAll`, `LinkedHashMap<String, List<VEvent>>`) — ein Serien-Master und
seine `RECURRENCE-ID`-Override-Komponenten können als getrennte
CalDAV-Ressourcen zurückkommen und müssen vor der Expansion zusammengeführt
werden. Fehlt die `UID` auf einer Komponente, wirft der Adapter eine
`CalDavCalendarSourceException`.

Innerhalb einer `UID`-Gruppe (`expandSeries`) wird die Komponente ohne
`RECURRENCE-ID`-Property als Master behandelt, jede Komponente mit
`RECURRENCE-ID` als Override. Fehlt ein Master (nur Override-Komponenten
vorhanden), wirft der Adapter eine `CalDavCalendarSourceException`.

### Einzeltermin (kein `RRULE` am Master)

Trägt der Master kein `RRULE`, wird er unverändert 1:1 auf genau ein
`SourceEvent` (`core/domain/SourceEvent.java`) abgebildet (`toSingleSourceEvent`),
`sourceUid` bleibt die reine `VEVENT`-`UID`, `recurring = false`.

### Wiederkehrende Serie (`RRULE` am Master)

Trägt der Master ein `RRULE`, wird die Serie ab `masterDtStart` expandiert
(`expandRecurringSeries`):

- Der `RRULE`-Wert wird über `net.fortuna.ical4j.model.Recur` geparst
  (`new Recur<ZonedDateTime>(rruleProperty.getValue())`); ein nicht
  parsbarer oder nicht expandierbarer `RRULE`-Wert wirft eine
  `CalDavCalendarSourceException`.
- Die Vorkommen-Startzeitpunkte kommen aus
  `Recur.getDates(masterStart, UNBOUNDED_PAST, horizonEnd)`. `UNBOUNDED_PAST`
  ist eine feste Konstante (`0001-01-01T00:00Z`) — die Expansion ist rückwärts
  bewusst unbegrenzt. `horizonEnd` ist `now.plus(recurringEventHorizon)`, wobei
  `now = ZonedDateTime.now(clock)` (der injizierte `Clock`, pro `readEvents()`-
  Aufruf neu bestimmt) und `recurringEventHorizon` ein `Period`-
  Konstruktor-Parameter des Adapters ist, gebunden aus `relay.recurring-event-
  horizon` (`RelayProperties`, Default `P6M`) und global für alle
  konfigurierten Kalender geteilt.
- `EXDATE`-Werte des Masters werden vorab in ein `Set<ZonedDateTime>`
  aufgelöst (`exceptionDates`); jeder berechnete Vorkommen-Zeitpunkt, der
  darin enthalten ist, wird übersprungen — kein `SourceEvent` für diesen
  Zeitpunkt.
- `RECURRENCE-ID`-Overrides werden vorab in eine `Map<ZonedDateTime, VEvent>`
  aufgelöst (`overridesByRecurrenceId`), Schlüssel ist der
  `RECURRENCE-ID`-Wert. Tragen mehrere Override-Komponenten dieselbe
  `RECURRENCE-ID`, gewinnt die mit der höheren `SEQUENCE` (`sequenceNumber`,
  Default `0` ohne `SEQUENCE`-Property).
- `EXDATE`- und `RECURRENCE-ID`-Werte werden mit derselben "Form"
  (`VALUE=DATE` ja/nein, `TZID`-Zone bzw. UTC) wie `masterDtStart` interpretiert
  (`DateForm`-Record, einmalig pro Master berechnet, `formOf`) — beide teilen
  laut RFC 5545 die Form ihres Masters.
- Für jeden verbleibenden, aufsteigend sortierten Vorkommen-Zeitpunkt wird
  `sourceUid = uid + "#" + occurrenceStart.toInstant()` gebildet (der
  zusammengesetzte Schlüssel aus `docs/domain.md`, Abschnitt
  "Zusammengesetzter `sourceUid` für wiederkehrende Termine"):
  - **Kein Override für diesen Zeitpunkt:** `start`/`end` ergeben sich aus
    `occurrenceStart` plus der Master-Dauer (`masterDtEnd - masterDtStart`),
    `allDay`/`busy` kommen vom Master, `recurring = true`.
  - **Override mit `STATUS:CANCELLED`:** das Vorkommen wird komplett
    ausgelassen — kein `SourceEvent`, analog zu einem `EXDATE`-Treffer.
  - **Override ohne `STATUS:CANCELLED`:** `start`/`end`/`allDay`/`busy` kommen
    vom Override-`VEVENT` selbst (eigenes `DTSTART`/`DTEND`), `sourceUid`
    bleibt der oben gebildete, auf dem ursprünglichen Serien-Zeitpunkt
    basierende Schlüssel, `recurring = true`.
  - `cancelled` jedes ausgegebenen Vorkommens ist `true`, sobald der Master
    selbst `STATUS:CANCELLED` trägt (`masterCancelled`) — die gesamte Serie
    wird dadurch **nicht** aus der Ausgabe entfernt, sondern jedes Vorkommen
    trägt das Flag weiter (siehe `docs/features/event-filtering.md`).

### `DTSTART`/`DTEND` → `ZonedDateTime`

Pflichtfelder auf jeder Master-, Override- oder Einzeltermin-Komponente;
fehlen sie, wirft der Adapter eine `CalDavCalendarSourceException`
(`requireDtStart`/`requireDtEnd`). Umwandlung in `ZonedDateTime`
(`toZonedDateTime`):

- Ist die Property `VALUE=DATE` (ganztägig), wird Mitternacht in der fest
  verdrahteten Zone `ALL_DAY_ZONE` (`Europe/Berlin`) verwendet und `allDay =
  true` gesetzt.
- Andernfalls, trägt die Property ein `TZID`-Parameter, wird die
  `ZonedDateTime` mit dieser `ZoneId` gebaut (`ZoneId.of(tzId)`).
- Andernfalls, falls die Property als UTC markiert ist (`Z`-Suffix), wird
  `ZoneOffset.UTC` verwendet.
- Fehlen alle drei (weder `VALUE=DATE` noch `TZID` noch UTC-Designator),
  wirft der Adapter eine `CalDavCalendarSourceException`.

Jeder Fehlerfall (unerwarteter Statuscode, IO-Fehler, malformed XML,
malformed ICS, fehlende Pflichtfelder, fehlgeschlagener
`CalendarReplicaStore`-Zugriff) resultiert in einer
`CalDavCalendarSourceException` (`adapters/outbound/caldav/
CalDavCalendarSourceException.java`), die von der Anwendungsschicht pro
Poll-Zyklus behandelt wird (siehe `scheduling.md`). Eine
`CalendarReplicaStoreException` aus `CalendarReplicaStore.loadSyncToken()`,
`loadAllResources()`, `applyDelta(...)` oder `resetTo(...)` wird dabei
unverpackt bis `readEvents()` durchgereicht und dort in eine
`CalDavCalendarSourceException` gewrappt — der gesamte Poll-Zyklus bricht
ab, exakt wie bei einem fehlgeschlagenen `calendar-query`-Request.

## Konfiguration: `relay.calendars[].delta-sync-enabled`

Pro-Kalender-Feld (`RelayProperties.CalendarConfig#deltaSyncEnabled`),
Default `true`. Bewusst pro Kalender statt global: Ob `sync-collection`
funktioniert, ist eine Server-Fähigkeit, die zwischen mehreren konfigurierten
Kalendern unterschiedlicher Anbieter legitim unterschiedlich ausfallen kann.
Ein Server, der `sync-collection` nicht unterstützt, wird ohnehin automatisch
erkannt und fällt selbstständig auf `calendar-query` zurück (siehe
"Erkennung fehlender Server-Unterstützung" oben) — `delta-sync-enabled:
false` ist ein manueller Notausschalter für den Fall, dass diese automatische
Erkennung selbst nicht wie erwartet funktioniert oder ein Server-spezifischer
Bug im `sync-collection`-Codepfad auftritt, nicht der reguläre Weg, einen
nicht unterstützenden Server zu behandeln.

## Instanziierung: kein Spring-Singleton

`CalDavCalendarSourceAdapter` ist trotz `@InfrastructureServiceAdapter`
**kein** auto-gescannter, parameterloser Spring-Bean — der Konstruktor nimmt
die Collection-URL, die Zugangsdaten, den `Clock`, den
`recurringEventHorizon`, den pro Kalender konstruierten
`CalendarReplicaStore` und `deltaSyncEnabled` entgegen:

```java
public CalDavCalendarSourceAdapter(
        HttpClient httpClient,
        URI calendarCollectionUri,
        String username,
        String password,
        Clock clock,
        Period recurringEventHorizon,
        CalendarReplicaStore calendarReplicaStore,
        boolean deltaSyncEnabled)
```

`RelayWiringConfiguration` konstruiert pro Eintrag in `relay.calendars` eine
eigene Instanz per `new` — inklusive eines eigenen, frisch konstruierten
`JpaCalendarReplicaStoreAdapter(calendarReplicaResourceJpaRepository,
calendarSyncTokenJpaRepository, calendar.id())` — analog zum bereits
bestehenden Muster für `stateStore` und `pendingCreationQueue`; die dabei
trotzdem automatisch registrierten Bean-Definitionen werden von
`PerCalendarComponentBeanDefinitionPruner` vor der Singleton-Vorinstanziierung
entfernt (beide, `CalDavCalendarSourceAdapter` und
`JpaCalendarReplicaStoreAdapter`, stehen in dessen Klassenliste). Siehe
[`scheduling.md`](scheduling.md#multi-kalender-verdrahtung) für den vollen
Mechanismus.

## Bewusst nicht gebaut

- **Kein proaktiver `PROPFIND`-Fähigkeits-Check** (`<D:supported-report-set>`)
  vor dem ersten `sync-collection`-Versuch — stattdessen rein reaktive
  Erkennung (siehe "Erkennung fehlender Server-Unterstützung" oben).
- **Kein `calendar-multiget`-Folge-Request.** Der Adapter geht davon aus, dass
  der Server `calendar-data` bereits inline in der `sync-collection`-Antwort
  liefert, wenn es im `<D:prop>` angefordert wird.
- **Kein Pruning/keine Größenbegrenzung der lokalen `CalendarReplicaStore`-
  Replik** — sie wächst mit der Anzahl je vom Server gemeldeter `href`s.
- **Kein `<D:limit>`-Element** zur serverseitigen Pagination sehr großer
  Deltas (RFC 6578, optional).
- **Keine ETag-basierte clientseitige Änderungserkennung** als Ergänzung oder
  Ersatz für die serverseitige Delta-Antwort — der ETag wird nur informativ
  gespeichert, nie verglichen.
- **Keine `time-range`-Filterung** im `calendar-query`-Filter.
- **Kein `RDATE`** — nur `RRULE`, `EXDATE` und `RECURRENCE-ID` werden bei der
  Serien-Expansion ausgewertet (siehe "Wiederkehrende Serie" oben); `RDATE`
  wird nicht gesondert unterstützt.
