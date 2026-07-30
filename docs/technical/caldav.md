# CalDAV-Adapter (`CalendarSource`)

`CalDavCalendarSourceAdapter`
(`adapters/outbound/caldav/CalDavCalendarSourceAdapter.java`) implementiert
den Port `CalendarSource` (`ports/outbound/CalendarSource.java`) und liest
den vollständigen aktuellen Bestand an `VEVENT`s aus einer privaten
CalDAV-Kalender-Collection über ein WebDAV `REPORT calendar-query` nach RFC
4791.

## Anfrage: `calendar-query` REPORT

Es wird immer eine vollständige Abfrage ohne Zeitraum-Einschränkung und ohne
`sync-collection`-Token gestellt (Delta-Sync nach RFC 6578 ist bewusst noch
nicht gebaut, siehe `CLAUDE.md`, Abschnitt „Deliberately deferred“). Der
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
von Basic-Auth auf einem `REPORT` ausgeben.

## HTTP-Client

Verwendet wird `java.net.http.HttpClient` (JDK-Bordmittel, kein zusätzlicher
HTTP-Client als Dependency). Eine einzige `HttpClient`-Instanz wird als
Spring-Bean (`RelayWiringConfiguration#relayCalDavHttpClient`) angelegt und
von **allen** konfigurierten Kalender-Adapterinstanzen gemeinsam genutzt.

## Antwortverarbeitung

1. **XML-Parsing der Multistatus-Antwort**: über einen bewusst gehärteten
   `DocumentBuilder` (`newSecureDocumentBuilder()`) — DOCTYPE-Deklarationen
   sind verboten, externe General-/Parameter-Entities sind deaktiviert,
   XInclude ist aus, Entity-Referenzen werden nicht expandiert. Das schützt
   gegen XXE-artige Angriffe über eine kompromittierte oder böswillige
   CalDAV-Antwort.
2. **Extraktion der `calendar-data`-Blobs**: Für jedes `<D:response>` werden
   dessen `<D:propstat>`-Elemente durchsucht; nur `propstat`-Blöcke mit
   Erfolgsstatus (HTTP-Statuszeile beginnt mit `2`) werden berücksichtigt.
   Aus jedem erfolgreichen `propstat` wird der Textinhalt von
   `<C:calendar-data>` als roher ICS-Blob gesammelt.
3. **ICS-Parsing pro Blob**: jeder Blob wird über `ical4j`
   (`net.fortuna.ical4j`, `CalendarBuilder`) geparst; alle enthaltenen
   `VEVENT`-Komponenten werden extrahiert.

## Mapping `VEVENT` → `SourceEvent`

Alle über sämtliche `calendar-data`-Blobs einer Antwort geparsten `VEVENT`s
werden zuerst per `UID` gruppiert (`expandAll`, `LinkedHashMap<String,
List<VEvent>>`) — ein Serien-Master und seine `RECURRENCE-ID`-Override-
Komponenten können als getrennte CalDAV-Ressourcen zurückkommen und müssen
vor der Expansion zusammengeführt werden. Fehlt die `UID` auf einer
Komponente, wirft der Adapter eine `CalDavCalendarSourceException`.

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
malformed ICS, fehlende Pflichtfelder) resultiert in einer
`CalDavCalendarSourceException` (`adapters/outbound/caldav/
CalDavCalendarSourceException.java`), die von der Anwendungsschicht pro
Poll-Zyklus behandelt wird (siehe `scheduling.md`).

## Instanziierung: kein Spring-Singleton

`CalDavCalendarSourceAdapter` ist trotz `@InfrastructureServiceAdapter`
**kein** auto-gescannter, parameterloser Spring-Bean — der Konstruktor nimmt
die Collection-URL und die Zugangsdaten pro Kalender entgegen:

```java
public CalDavCalendarSourceAdapter(
        HttpClient httpClient, URI calendarCollectionUri, String username, String password)
```

`RelayWiringConfiguration` konstruiert pro Eintrag in `relay.calendars` eine
eigene Instanz per `new`; die dabei trotzdem automatisch registrierte
Bean-Definition wird von `PerCalendarComponentBeanDefinitionPruner` vor der
Singleton-Vorinstanziierung entfernt. Siehe
[`scheduling.md`](scheduling.md#multi-kalender-verdrahtung) für den vollen
Mechanismus.

## Bewusst nicht gebaut

- Keine `sync-collection`-Delta-Abfrage (RFC 6578) — jeder Poll-Zyklus liest
  den vollständigen aktuellen Bestand neu.
- Keine `time-range`-Filterung im `calendar-query`-Filter.
- Kein `RDATE` — nur `RRULE`, `EXDATE` und `RECURRENCE-ID` werden bei der
  Serien-Expansion ausgewertet (siehe "Wiederkehrende Serie" oben); `RDATE`
  wird nicht gesondert unterstützt.
