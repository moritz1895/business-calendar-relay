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

Jede `VEVENT`-Komponente wird direkt (1:1, keine `RRULE`-Expansion — wiederkehrende
Serien sind out of scope, siehe `CLAUDE.md`) auf ein `SourceEvent`
(`core/domain/SourceEvent.java`) abgebildet:

- `UID` — Pflichtfeld; fehlt es, wirft der Adapter eine
  `CalDavCalendarSourceException`.
- `DTSTART`/`DTEND` — Pflichtfelder, werden in `ZonedDateTime` umgewandelt:
  - Trägt die Property ein `TZID`-Parameter, wird die `ZonedDateTime` mit
    dieser `ZoneId` gebaut (`ZoneId.of(tzId)`).
  - Andernfalls, falls die Property als UTC markiert ist (`Z`-Suffix), wird
    `ZoneOffset.UTC` verwendet.
  - Fehlen beide (weder `TZID` noch UTC-Designator), wirft der Adapter eine
    `CalDavCalendarSourceException` — ein reines Datum ohne Zeitkomponente
    oder ohne Zeitzonenbezug wird nicht unterstützt.

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
- Keine Expansion wiederkehrender Serien (`RRULE`); nur die konkrete
  `VEVENT`-Komponente wird gelesen.
