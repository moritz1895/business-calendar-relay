# Feature: CalDAV Delta-Sync via `sync-collection` (RFC 6578)

Kein GitHub-Issue: Diese Spec setzt direkt `CLAUDE.md`s Abschnitt „Deliberately
deferred" um, das Delta-Sync seit sehr früher Projektphase als den
ausdrücklich nächsten Punkt auf der Roadmap vermerkt: *"Delta detection: full
poll-and-diff first; CalDAV `sync-collection` (RFC 6578) sync-token support
comes later once the basic relay works end-to-end. Still deferred — current
next feature on the roadmap."* Der Rest des Relays (Erstellungs-Filter,
Burst-Filter-Initialisierung) ist inzwischen gebaut und produktiv einsatzbereit
(Issues #3, #16). Es gibt weder eine vorherige Konversation noch besprochene
Eckpunkte zu diesem Feature — diese Spec ist die primäre Quelle des
technischen Entwurfs, nicht die Verschriftlichung eines Gesprächs.

## Kontext: Betriebskosten des heutigen Full-Poll-Ansatzes

`CalDavCalendarSourceAdapter.readEvents()` stellt bei **jedem** Poll-Zyklus
eine vollständige `calendar-query` REPORT-Anfrage ohne Zeitraum-Einschränkung
gegen die konfigurierte Kalender-Collection und erhält dabei den kompletten
`calendar-data`-Inhalt **jeder** Ressource der Collection zurück — bei
`RELAY_POLL_INTERVAL` von z. B. 5 Minuten und einem Kalender mit mehrjähriger
Historie (der Erstellungs-Filter aus `event-filtering.md` verhindert zwar
Mail-Flut, ändert aber nichts daran, dass `readEvents()` selbst weiterhin
jeden historischen Termin vollständig überträgt) bedeutet das hunderte oder
tausende XML-/ICS-Bytes, die alle zwölf Zyklen pro Stunde erneut vom
CalDAV-Server angefordert und übertragen werden, obwohl sich die
überwältigende Mehrheit der Termine zwischen zwei Polls nicht verändert hat.
Das belastet sowohl den CalDAV-Server (der bei jedem Request die komplette
Collection erneut auflisten und serialisieren muss) als auch die
Netzwerkverbindung — ohne jeden fachlichen Zusatznutzen gegenüber einer
Anfrage, die nur das seit dem letzten Poll tatsächlich Geänderte liefert.

RFC 6578 (`sync-collection` REPORT) ist genau für dieses Problem entworfen:
Der Client hält einen opaken, serverseitig vergebenen Sync-Token; eine
`sync-collection`-Anfrage mit diesem Token liefert ausschließlich die seit
dem letzten Abgleich neu hinzugekommenen, geänderten oder gelöschten
Ressourcen der Collection zurück, plus einen neuen Token für den nächsten
Abgleich.

## Feature-Zusammenfassung

`CalDavCalendarSourceAdapter` wechselt von der bisherigen, immer
vollständigen `calendar-query` REPORT-Anfrage auf eine `sync-collection`
REPORT-Anfrage (RFC 6578) mit persistiertem Sync-Token, sobald der
CalDAV-Server dieses Verfahren für die konfigurierte Collection unterstützt.
Der Adapter hält dafür pro Kalender eine lokale Replik aller bekannten
CalDAV-Ressourcen (roher `calendar-data`-Inhalt plus ETag, indiziert über
deren `href`) und aktualisiert diese Replik inkrementell anhand der vom
Server gemeldeten Deltas, statt sie bei jedem Poll komplett neu vom Server
anzufordern. **`CalendarSource.readEvents()` liefert dabei unverändert bei
jedem Aufruf den vollständigen, aktuellen `SourceEvent`-Bestand** — die
Vollständig-immer-Vertrag-Garantie des Ports bleibt exakt bestehen (siehe
`event-filtering.md`s "Out of scope"-Eintrag dazu); es ändert sich
ausschließlich, **wie** der Adapter intern zu diesem vollständigen Bestand
kommt. Ein CalDAV-Server, der `sync-collection` nicht unterstützt oder dessen
Sync-Token ungültig wird (RFC 6578s `403`/`507`-Fälle), lässt den Adapter
automatisch auf die bisherige vollständige `calendar-query`-Anfrage
zurückfallen — ohne Datenverlust und ohne manuelles Eingreifen.

Diese Feature ist eine reine **Netzwerk-/Server-Entlastungs-Optimierung**,
keine CPU-Optimierung: Die komplette Serien-Expansion (`RRULE`, `EXDATE`,
`RECURRENCE-ID`-Auflösung, siehe `event-filtering.md`) läuft bei jedem
`readEvents()`-Aufruf weiterhin vollständig über **alle** lokal bekannten
Ressourcen — nicht nur die zuletzt geänderten. Das ist kein Kompromiss,
sondern eine notwendige Konsequenz aus dem nach vorne gleitenden
Wiederholungs-Zeitfenster (siehe "Zusammenspiel von Ressourcen-Deltas und
dem zusammengesetzten `sourceUid`-Schema" unten für die vollständige
Begründung).

## Design-Kernentscheidung — muss vor der Umsetzung verstanden sein

> **`sync-collection` liefert Deltas pro roher CalDAV-Ressource (ein
> `.ics`-Objekt pro `href` — bei einer wiederkehrenden Serie also die
> gesamte Serie inklusive aller `RECURRENCE-ID`-Overrides in einem Stück),
> nicht pro expandiertem Vorkommen. Das bestehende zusammengesetzte
> `sourceUid`-Schema (`<Serien-UID>#<ursprünglicher Vorkommen-Instant>`) aus
> `event-filtering.md` bleibt davon vollständig unberührt, weil die beiden
> Konzepte auf verschiedenen Ebenen liegen und nie direkt aufeinandertreffen:
> Ein Ressourcen-Delta verändert ausschließlich die lokal zwischengespeicherten
> **rohen** `calendar-data`-Blobs (`CalendarReplicaStore`, neu, siehe unten);
> die bereits bestehende, durch dieses Feature nicht angerührte
> `expandAll`/`expandSeries`-Pipeline in `CalDavCalendarSourceAdapter` läuft
> bei jedem `readEvents()`-Aufruf unverändert über die Gesamtmenge aller
> lokal bekannten rohen Ressourcen (nicht nur die zuletzt gelieferten
> Deltas) und erzeugt daraus exakt dieselben, pro Vorkommen zusammengesetzten
> `sourceUid`s wie heute. `RelayDiffPlanner`, `RelayState`, `SourceEvent`
> und der gesamte Rest der Domänenschicht sehen von `sync-collection`,
> Sync-Tokens, `href`s oder ETags nichts — sie sehen weiterhin ausschließlich
> die immer vollständige `SourceEvent`-Liste, die `readEvents()` schon immer
> zurückgibt.**

Diese Trennung ist absichtlich so gezogen: Sie hält jede
CalDAV-Protokolldetail (Sync-Token, ETag, `href`, das rohe
`calendar-data`-Delta) an der Adapter-Grenze, exakt wie schon `RRULE`,
`EXDATE` und `RECURRENCE-ID` in `event-filtering.md` — und sie vermeidet
jede Notwendigkeit, `RelayDiffPlanner`s absenz-basierte Cancel-Erkennung
(„ein `prior.active()`-Eintrag, dessen `sourceUid` in `currentEvents` fehlt,
wird storniert") um ein explizites, aus CalDAV-Deltas abgeleitetes
Lösch-Signal zu erweitern. Eine ernsthaft erwogene Alternative — `readEvents()`
selbst auf eine Delta-Rückgabeform umzustellen (z. B. `SourceEventDelta` mit
expliziten „geändert"/„gelöscht"-Vorkommen) und `RelayDiffPlanner` sowie
`StateStore` entsprechend umzubauen — wurde verworfen: Sie hätte die
Vollständig-immer-Garantie gebrochen, auf der nicht nur `RelayDiffPlanner`s
Cancel-Zweig, sondern auch der komplette Capture-Schritt der
Burst-Filter-Initialisierung (`captureInitializationQueue`,
`docs/features/burst-filter-initialization.md`) aufbaut — beide verlassen
sich darauf, dass `currentEvents` bei jedem Aufruf **alle** aktuell im
Quellkalender sichtbaren Termine enthält, nicht nur die seit dem letzten Poll
veränderten. Ein Umbau dieser Tragweite quer durch drei Schichten stünde in
keinem Verhältnis zum eigentlichen Ziel dieser Feature (Server-/Netzwerklast
senken), das sich vollständig innerhalb der Adapter-Grenze erreichen lässt.

## Akteure

Unverändert gegenüber `relay-orchestration.md`, `event-filtering.md` und
`burst-filter-initialization.md`: **Scheduler** ist der einzige Akteur.

## Use Case: Poll and Relay Source Calendar — keine funktionale Änderung

Command, Vorbedingungen, `RelayCycleResult`, der komplette
Capture-and-Drain-/Steady-State-Ablauf aus
`burst-filter-initialization.md` und jede Verarbeitungsregel aus
`event-filtering.md`/`relay-orchestration.md` bleiben **exakt** wie
spezifiziert. `PollAndRelaySourceCalendarService.pollAndRelay()` ruft weiterhin
unverändert `calendarSource.readEvents()` auf und erhält davon weiterhin eine
vollständige `List<SourceEvent>` zurück — der Aufrufer kann nicht
unterscheiden, ob diese Liste aus einer vollständigen `calendar-query`- oder
einer intern durch `sync-collection`-Deltas rekonstruierten Antwort stammt.
Das ist die direkte, beabsichtigte Konsequenz der Design-Kernentscheidung
oben: **Diese Feature ändert keine einzige Zeile in `core/app` oder
`core/domain`.** Weder `RelayDiffPlanner`, noch `RelayState`, noch
`SourceEvent`, noch `RelayAction`, noch `PollAndRelaySourceCalendarService`
werden angefasst — die vollständige Umsetzung liegt in
`adapters/outbound/caldav`, einem neuen Outbound-Port plus dessen
JPA-Adapter, und der Verdrahtung in `RelayWiringConfiguration`.

Das ist ein bemerkenswerter Unterschied zu den beiden vorherigen Features:
`event-filtering.md` musste `RelayDiffPlanner.plan(...)`s Signatur erweitern,
`burst-filter-initialization.md` musste `pollAndRelay()`s internen Ablauf
umbauen. Delta-Sync braucht beides nicht, weil sein gesamter fachlicher
Effekt — weniger Serverlast beim Lesen — bereits vollständig hinter dem
bestehenden `CalendarSource`-Port-Vertrag verborgen werden kann.

## Der Delta-Sync-Mechanismus im CalDAV-Adapter

### Neue Anfrage: `sync-collection` REPORT

Statt der bisherigen `calendar-query` (siehe `docs/technical/caldav.md`)
stellt der Adapter, sobald ein Sync-Token für diesen Kalender bekannt ist
(oder initial mit leerem Token, siehe unten), folgenden REPORT-Body:

```xml
<?xml version="1.0" encoding="utf-8" ?>
<D:sync-collection xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:sync-token>{bisheriger Token, oder leer für den initialen Sync}</D:sync-token>
  <D:sync-level>1</D:sync-level>
  <D:prop>
    <D:getetag/>
    <C:calendar-data/>
  </D:prop>
</D:sync-collection>
```

- Methode `REPORT` gegen dieselbe `calendarCollectionUri` wie heute, Header
  `Depth: 1` bleibt aus Konsistenzgründen mit der bestehenden
  `calendar-query`-Anfrage gesetzt (manche Serverimplementierungen prüfen den
  Header zusätzlich zu `sync-level`; beides zu senden ist strikt sicherer als
  nur eines von beiden, siehe "Weitere Entscheidungen" unten). Content-Type
  und Basic-Auth-Header unverändert wie bei der bestehenden
  `calendar-query`-Anfrage.
- `<D:sync-token>` **leer** bedeutet laut RFC 6578 §3.2 "initialer Sync":
  Der Server liefert die **vollständige** aktuelle Collection (funktional
  äquivalent zur bisherigen `calendar-query`, nur über einen anderen
  REPORT-Typ) plus einen frischen Sync-Token für künftige Delta-Anfragen.
  Das ist der Fall, wenn für diesen Kalender noch kein Token persistiert ist
  (allererster Poll nach Rollout dieser Feature, oder nach einem erzwungenen
  Full-Resync, siehe unten).
- Anders als `calendar-query` unterstützt `sync-collection` laut RFC 6578
  **kein** `<C:filter>`-Element — die Antwort kann grundsätzlich jede
  Ressource der Collection enthalten, nicht nur solche mit `VEVENT`. Das ist
  hier folgenlos: `parseVEvents(...)` extrahiert bereits heute nur
  `VEVENT`-Komponenten aus jedem `calendar-data`-Blob
  (`calendar.getComponents(Component.VEVENT)`) und liefert für eine Ressource
  ohne `VEVENT` (z. B. eine reine `VTODO`- oder `VJOURNAL`-Ressource) einfach
  eine leere Liste — keine Codeänderung nötig, die bestehende Filterung „nur
  `VEVENT`" wirkt automatisch weiter.

### Antwortverarbeitung

Wie die bestehende `calendar-query`-Antwort ein `207 Multi-Status` mit
`<D:response>`-Elementen pro Ressource, zusätzlich mit einem
Top-Level-`<D:sync-token>`-Element (Geschwister der `<D:response>`-Elemente,
direkt unter `<D:multistatus>`) mit dem **neuen** Token für den nächsten
Abgleich. Pro `<D:response>`:

- **Neu oder geändert:** `<D:propstat>` mit Erfolgsstatus (wie bei
  `calendar-query` über `isSuccessStatus(...)` geprüft), enthält
  `<D:getetag>` und `<C:calendar-data>`. Wird als
  `CachedCalendarResource(href, etag, rawCalendarData)` behandelt (siehe
  Port-Änderungen unten).
- **Gelöscht:** `<D:status>HTTP/1.1 404 Not Found</D:status>` direkt unter
  `<D:response>` (kein `<D:propstat>` mit Erfolgsstatus). Der `href` dieses
  `<D:response>`-Elements wird als entfernt behandelt.
- Jedes andere, nicht erkannte `<D:propstat>`-Statuszeilenmuster wird — wie
  heute bei `calendar-query` über `isSuccessStatus(...)` — übersprungen und
  verändert den lokalen Replik-Stand für diesen `href` nicht; das ist
  dieselbe bereits etablierte Nachsichtigkeit, mit der der Adapter heute
  einzelne fehlgeschlagene `propstat`-Blöcke einer ansonsten erfolgreichen
  Multistatus-Antwort behandelt.

Eine neue private Methode `parseSyncCollectionResponse(String multiStatusXml)`
liefert intern ein Tripel `(newSyncToken, changedResources, removedHrefs)`,
strukturell parallel zu `extractCalendarDataBlobs(...)`, aber `href`- und
`getetag`-bewusst statt nur den `calendar-data`-Textinhalt zu sammeln.

### Initialer Sync (kein Token vorhanden)

```
token = calendarReplicaStore.loadSyncToken()   // null, wenn nie zuvor synchronisiert
if (token == null) {
    response = executeSyncCollection(emptyToken)
    calendarReplicaStore.resetTo(response.newSyncToken(), response.changedResources())
}
```

Dieser Fall tritt genau einmal pro Kalender ein: beim allerersten Poll nach
Rollout dieser Feature (egal, ob der Kalender selbst schon lange läuft — ein
bestehender `RelayState`-Bestand bleibt davon komplett unberührt, siehe
"Weitere Entscheidungen") oder nach einem erzwungenen Full-Resync. Kostet
dieselbe Serverlast wie die bisherige `calendar-query`-Anfrage — kein
Regressionsrisiko gegenüber heute.

### Inkrementeller Sync (Token vorhanden)

```
response = executeSyncCollection(token)
calendarReplicaStore.applyDelta(response.newSyncToken(), response.changedResources(), response.removedHrefs())
```

### Erzwungener Full-Resync bei ungültigem Token

RFC 6578 §3.2 verlangt vom Server `403 Forbidden` mit dem
Precondition-Element `<D:valid-sync-token/>` innerhalb eines
`<D:error>`-Bodys, wenn der übergebene Token nicht (mehr) einem bekannten
Collection-Zustand entspricht (z. B. Server-seitige Sync-Historie wurde
abgeschnitten oder der Report wurde zwischenzeitlich deaktiviert und wieder
aktiviert). Manche Implementierungen antworten stattdessen mit
`507 Insufficient Storage`, wenn der Server grundsätzlich keine ausreichende
Sync-Historie mehr vorhält, um den Delta zu berechnen. Beide Fälle werden
identisch behandelt:

```
catch (InvalidSyncTokenException) {
    response = executeSyncCollection(emptyToken)   // erzwungener initialer Sync
    calendarReplicaStore.resetTo(response.newSyncToken(), response.changedResources())
}
```

Kein Datenverlust: `resetTo(...)` überschreibt die lokale Replik komplett mit
dem aktuellen Server-Stand — anschließend läuft die bestehende
`expandAll`-Pipeline exakt wie beim initialen Sync über die (jetzt wieder
vollständige) Replik. Ein bereits bestehender `RelayState`-Bestand ist davon
nicht betroffen (siehe "Zusammenspiel..." unten) — im schlimmsten Fall kostet
dieser eine Zyklus dieselbe Serverlast wie ein heutiger `calendar-query`-Poll.

### Fallback bei fehlender Server-Unterstützung

Unterstützt der CalDAV-Server `sync-collection` grundsätzlich nicht,
antwortet er typischerweise mit einem Fehlerstatus, der **nicht** das
`<D:valid-sync-token/>`-Precondition-Element trägt (z. B. `403 Forbidden` mit
`<D:supported-report/>`-Precondition nach RFC 3253, `501 Not Implemented`,
oder `415 Unsupported Media Type`). Der Adapter unterscheidet nicht
kleinteilig zwischen diesen Fällen: **Jede** `sync-collection`-Antwort, die
weder `207 Multi-Status` noch der oben beschriebene
`<D:valid-sync-token/>`-Fall ist, wird als "Server unterstützt
`sync-collection` für diese Collection nicht" gewertet. Der Adapter setzt
daraufhin für die verbleibende Lebensdauer dieser Instanz (In-Memory-Flag,
nicht persistiert) endgültig auf die bisherige, unveränderte
`calendar-query`-Anfrage zurück und loggt das einmalig auf `WARN`-Niveau mit
Statuscode und Response-Body für die Betriebsdiagnose. Ein Neustart des
Prozesses versucht `sync-collection` erneut — im ungünstigsten Fall ein
einziger zusätzlicher, folgenloser Fehlschlag pro Neustart, kein
wiederholtes Problem innerhalb eines laufenden Prozesses.

## Zusammenspiel von Ressourcen-Deltas und dem zusammengesetzten `sourceUid`-Schema

Dies ist der Kern der technischen Herausforderung dieser Feature und wird
hier bewusst ausführlich hergeleitet.

**Das Problem:** `sync-collection` meldet Änderungen pro `href` — eine
CalDAV-Ressource, typischerweise eine `.ics`-Datei. Bei einer wiederkehrenden
Serie ist das **eine** Ressource für die **gesamte** Serie (Master-`VEVENT`
plus alle `RECURRENCE-ID`-Override-Komponenten in derselben Datei, dem
üblichen Fall für die meisten CalDAV-Server/-Clients — siehe
`event-filtering.md`s bereits bestehende Behandlung des selteneren Falls, in
dem ein Override als eigene Ressource ankommt). Ändert sich **ein einziges**
Vorkommen dieser Serie (z. B. eine Instanz wird verschoben, ein neues
`RECURRENCE-ID`-Override kommt hinzu), meldet der Server die **komplette**
Ressource als geändert — nicht "Vorkommen X der Serie Y hat sich geändert".
Der Server weiß nichts von der pro-Vorkommen-Expansion, die erst
`CalDavCalendarSourceAdapter.expandSeries(...)` clientseitig vornimmt; er
kennt nur rohe WebDAV-Ressourcen.

**Die Auflösung:** Es ist **nicht nötig**, dieses Problem aufzulösen, weil
kein Code-Pfad in diesem Projekt jemals ein Ressourcen-Delta direkt mit einem
einzelnen `sourceUid` in Verbindung bringen muss. Der Ablauf ist:

1. `sync-collection` liefert Deltas ausschließlich auf `href`-Ebene:
   "Ressource `X` hat jetzt diesen rohen `calendar-data`-Inhalt (mit neuem
   ETag)" oder "Ressource `X` existiert nicht mehr".
2. `CalendarReplicaStore` (neuer Port, siehe unten) hält für jeden `href` den
   zuletzt bekannten rohen `calendar-data`-Inhalt — **nicht** irgendeine
   bereits expandierte Vorkommen-Repräsentation. Ein Delta aktualisiert
   ausschließlich diese rohe Ablage: Upsert des neuen `calendar-data` für
   geänderte `href`s, Entfernen der Zeile für gelöschte `href`s.
3. Bei **jedem** `readEvents()`-Aufruf liest der Adapter über
   `calendarReplicaStore.loadAllResources()` **alle** aktuell bekannten
   rohen Ressourcen dieses Kalenders (nicht nur die zuletzt gelieferten
   Deltas), parst jede über das bereits bestehende `parseVEvents(...)` zu
   `VEvent`-Komponenten, und übergibt die **komplette** Menge unverändert an
   die bereits bestehende `expandAll(allVEvents, now)`-Methode — exakt
   dieselbe Methode, die heute mit dem vollständigen `calendar-query`-Ergebnis
   aufgerufen wird.
4. `expandAll`/`expandSeries` gruppieren wie heute zunächst nach `UID` (nicht
   nach `href` — mehrere `href`s können, im selteneren Override-als-eigene-
   Ressource-Fall, zur selben `UID`-Gruppe beitragen, exakt wie heute schon
   von `event-filtering.md` beschrieben) und expandieren jede Gruppe
   vollständig neu gegen das aktuelle `now`/`recurringEventHorizon` — mit
   demselben zusammengesetzten `sourceUid`-Schema
   (`<Serien-UID>#<ursprünglicher Vorkommen-Instant>`) wie heute.

Damit ist die Antwort auf "wie reconciled ein Ressourcen-Delta mit dem
Vorkommen-`sourceUid`-Schema" denkbar einfach: **Es reconciled nicht direkt —
es muss nicht, weil ein Ressourcen-Delta nie versucht, ein einzelnes
Vorkommen zu identifizieren.** Es aktualisiert nur die rohe Materialbasis,
aus der die bereits bestehende, durch dieses Feature unveränderte
Expansions-Pipeline bei jedem Aufruf ohnehin von Grund auf neu das komplette
Bild aller aktuellen Vorkommen berechnet. Eine geänderte Ressource lässt die
Expansion für **ihre** `UID`-Gruppe ein anderes Ergebnis liefern als beim
letzten Mal (neue/verschobene/entfernte Vorkommen mit entsprechend neuen/
geänderten/fehlenden `sourceUid`s); eine unveränderte Ressource liefert bei
gleichem `now` bis auf das Vorrücken des Wiederholungs-Zeitfensters exakt
dasselbe Ergebnis wie beim letzten Mal — `RelayDiffPlanner` sieht in beiden
Fällen nur das Endergebnis (eine vollständige `SourceEvent`-Liste) und
verhält sich exakt wie heute.

**Warum die rohe Ablage pro `href` statt pro `UID` indiziert wird:** Ein
`href` identifiziert laut WebDAV die Ressourcen-**Identität**, eine `UID` nur
deren **Inhalt**. `sync-collection` meldet Änderungen und Löschungen über
`href`, nicht `UID` — die lokale Replik muss also zwingend über denselben
Schlüssel indiziert sein wie die Server-Antwort, sonst lässt sich ein
gemeldetes Delta nicht anwenden. Die `UID` wird weiterhin erst beim Parsen
jedes rohen Blobs ermittelt (`requireUid(vevent)`, unverändert), nicht als
Teil des Replik-Schlüssels vorausgesetzt.

**Warum die rohe Ablage nicht bereits expandierte Vorkommen speichert:** Das
konfigurierte `recurring-event-horizon` gleitet mit jedem Poll-Zyklus nach
vorne, weil es relativ zu `now` berechnet wird (siehe
`event-filtering.md`s "Vorwärts-Deckelung der Serien-Expansion"). Eine
wiederkehrende Serie, deren zugrunde liegende CalDAV-Ressource sich **nie**
ändert, muss trotzdem bei jedem Poll-Zyklus **neue** Vorkommen offenbaren
können, sobald das Zeitfenster weiter fortschreitet — das passiert heute
automatisch, weil `readEvents()` bei jedem Aufruf komplett neu expandiert.
Würde die lokale Replik stattdessen bereits expandierte Vorkommen
zwischenspeichern und nur bei einem `sync-collection`-Delta neu berechnen,
würde eine untouched, aber unbegrenzt wiederkehrende Serie für immer bei den
zum Zeitpunkt des letzten tatsächlichen Deltas berechneten Vorkommen
einfrieren — ein stiller, schwerwiegender Regressionsfehler gegenüber dem
heutigen Verhalten. Deshalb: Die Replik speichert ausschließlich rohe,
unexpandierte `calendar-data`-Blobs; die vollständige Neu-Expansion bei jedem
`readEvents()`-Aufruf ist bewusst beibehalten, nicht wegoptimiert.

## Domain model additions

**Keine.** `SourceEvent`, `RelayState`, `RelayAction`, `RelayDiffPlanner`
bleiben byte-identisch zum heutigen Stand. Das ist eine direkte Konsequenz
der Design-Kernentscheidung oben: Jedes CalDAV-Protokolldetail, das diese
Feature einführt (Sync-Token, `href`, ETag, rohes `calendar-data`-Delta),
ist reines Adapter-/Port-Wissen, exakt wie `RRULE`/`EXDATE`/`RECURRENCE-ID`
bereits in `event-filtering.md` konsequent an der Adapter-Grenze gehalten
wurden — es hat in `core/domain` oder `core/app` nichts verloren.

## Port-Änderungen

### `CalendarSource` (bestehend)

**Unverändert.** `List<SourceEvent> readEvents()` bleibt exakt wie heute
spezifiziert: "Always a full snapshot, never a delta." Diese Feature ändert,
wie `CalDavCalendarSourceAdapter` intern zu diesem vollständigen Snapshot
kommt, nicht den Vertrag selbst.

### `CalendarReplicaStore` (neuer, dedizierter Outbound-Port)

**Entscheidung, analog zu ADR-008 (`PendingCreationQueue` als eigener Port
statt `StateStore`-Erweiterung):** Die lokale Ressourcen-Replik plus
Sync-Token bekommt einen eigenen, dedizierten Port statt einer Erweiterung
von `StateStore`. Begründung: `RelayState`s Invarianten (`lastKnownStart`/
`lastKnownEnd` nicht null, ein Eintrag pro `sourceUid`) beschreiben
Relay-**Vorkommen**-Bookkeeping; eine Ressourcen-Replik beschreibt
CalDAV-**Protokoll**-Bookkeeping (rohe Bytes, ETags, `href`s, ein Token) auf
einer komplett anderen Abstraktionsebene, mit einem komplett anderen
Schlüssel (`href` statt `sourceUid`). Eine Vermischung würde — wie schon bei
`PendingCreationQueue` entschieden — ein bereits klar geschnittenes
Wertobjekt für einen fachlich andersartigen Zustand verwässern.

```java
package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.List;
import ms.rohde.hexagonalarch.annotations.InfrastructureServicePort;
import org.jspecify.annotations.Nullable;

@InfrastructureServicePort
public interface CalendarReplicaStore {

    /**
     * Returns the persisted sync-token for this source calendar, or {@code null} if none
     * is stored yet -- either this calendar has never been synced under this feature, or
     * a prior forced resync cleared it via {@link #resetTo}. A {@code null} return means
     * the next sync-collection exchange must use an empty request token (initial sync).
     */
    @Nullable String loadSyncToken();

    /**
     * Returns every currently cached raw resource for this source calendar. Order is not
     * part of the contract -- callers group and expand by UID, not by insertion order.
     */
    List<CachedCalendarResource> loadAllResources();

    /**
     * Applies one incremental sync-collection delta in a single persistence operation:
     * upserts {@code upserted} (keyed by {@link CachedCalendarResource#href()}), removes
     * every entry whose {@code href} is in {@code removedHrefs}, and advances the stored
     * sync-token to {@code newSyncToken}.
     *
     * @throws CalendarReplicaStoreException if the underlying persistence operation fails
     */
    void applyDelta(String newSyncToken, List<CachedCalendarResource> upserted, List<String> removedHrefs);

    /**
     * Replaces the entire cached resource set and sync-token for this source calendar in
     * one shot. Used for an initial sync-collection exchange (empty request token) and for
     * a forced full resync after the server invalidates a previously stored token.
     *
     * @throws CalendarReplicaStoreException if the underlying persistence operation fails
     */
    void resetTo(String newSyncToken, List<CachedCalendarResource> resources);
}
```

- **Ein konfiguriertes Instanz pro Quellkalender**, exakt wie `StateStore`,
  `CalendarSource` und `PendingCreationQueue`.
- `loadSyncToken()` wirft bewusst unverpackt (analog zu
  `StateStore.loadAll()`/`PendingCreationQueue.loadAllOrderedByStart()`) —
  ein Fehler hier lässt `readEvents()` fehlschlagen, was denselben Effekt
  hat wie ein fehlgeschlagener `calendar-query`-Request heute.
- `applyDelta(...)` ist idempotent bezüglich wiederholter identischer
  Aufrufe (ein erneuter Aufruf mit denselben Argumenten führt zum selben
  Endzustand) — relevant, falls ein Absturz zwischen einem erfolgreichen
  `sync-collection`-Response und dem `applyDelta`-Aufruf passiert: Der
  nächste Poll fragt beim Server erneut mit dem noch **alten**, zuletzt
  erfolgreich persistierten Token an (der neue Token aus der verlorenen
  Antwort wurde ja nie gespeichert) und bekommt exakt dasselbe Delta ein
  zweites Mal — kein Datenverlust, keine Lücke.

### `CachedCalendarResource` (neuer, port-begleitender Werttyp)

Analog zu `BlockerMail`/`BlockerMailMethod` (`ports/outbound/BlockerMail.java`)
— ein Transport-/Protokoll-Werttyp, der neben dem Port liegt, statt in
`core/domain`, da er reines CalDAV-Wissen (roher ICS-Text, `href`, ETag)
trägt, keine fachliche Domain-Bedeutung:

```java
package ms.rohde.businesscalendarrelay.ports.outbound;

import java.util.Objects;

public record CachedCalendarResource(String href, String etag, String rawCalendarData) {

    public CachedCalendarResource {
        Objects.requireNonNull(href, "href must not be null");
        Objects.requireNonNull(etag, "etag must not be null");
        Objects.requireNonNull(rawCalendarData, "rawCalendarData must not be null");

        if (href.isBlank()) {
            throw new IllegalArgumentException("href must not be blank");
        }
        if (rawCalendarData.isBlank()) {
            throw new IllegalArgumentException("rawCalendarData must not be blank");
        }
    }
}
```

### `CalendarReplicaStoreException`

Analog zu `StateStoreException`/`PendingCreationQueueException`:

```java
package ms.rohde.businesscalendarrelay.ports.outbound;

public class CalendarReplicaStoreException extends RuntimeException {

    public CalendarReplicaStoreException(String message) {
        super(message);
    }

    public CalendarReplicaStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### `CalDavCalendarSourceAdapter` — Konstruktor-Änderung

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

Zwei zusätzliche Konstruktor-Parameter, analog zu den bereits bestehenden
Pro-Kalender-Parametern: `calendarReplicaStore` (pro Kalender neu
konstruiert, wie `stateStore` und `pendingCreationQueue`) und
`deltaSyncEnabled` (siehe Konfiguration unten). Ist `deltaSyncEnabled ==
false`, verhält sich `readEvents()` exakt wie vor dieser Feature — die
bisherige `calendar-query`-Anfrage, unverändert, `calendarReplicaStore` wird
in diesem Fall nie berührt.

`readEvents()`s öffentliche Signatur (`List<SourceEvent> readEvents()`)
bleibt unverändert; nur der interne Ablauf verzweigt jetzt zwischen
"delta-sync aktiv und vom Server unterstützt" (siehe oben) und "Legacy
`calendar-query`" (deaktiviert, oder Server-seitig als nicht unterstützt
erkannt).

### `StateStore`, `PendingCreationQueue`, `BurstBudget` (bestehend)

Alle drei bleiben **vollständig unverändert** — weder Methodensignaturen
noch transportierte Datenformen wachsen durch diese Feature.

### `PollAndRelaySourceCalendarUseCase` (inbound)

Unverändert (`pollAndRelay()` bleibt parameterlos). Diese Feature fügt der
Use-Case-Instanz keinen einzigen neuen Konfigurationswert hinzu — alles, was
sie braucht (`CalendarReplicaStore`, `deltaSyncEnabled`), ist ausschließlich
Konfiguration von `CalDavCalendarSourceAdapter`, das hinter dem
`CalendarSource`-Port für die Use-Case-Schicht unsichtbar bleibt.

## Persistenz

### Tabelle `calendar_replica_resource`

Neue Entity `CalendarReplicaResourceEntity`
(`adapters/outbound/persistence/CalendarReplicaResourceEntity.java`),
strukturell ein direktes Geschwister von `PendingCreationEntity`:

| Spalte | Typ (Java) | Nullable | Beschreibung |
|---|---|---|---|
| `source_calendar_id` | `String` | nein, Teil des zusammengesetzten PK | wie bei `relay_state`/`pending_creation` |
| `href` | `String` | nein, Teil des zusammengesetzten PK | WebDAV-Ressourcen-`href`, wie vom Server gemeldet |
| `etag` | `String` | nein | zuletzt bekannter ETag dieser Ressource, rein informativ (kein eigener Vergleich durch diesen Adapter -- der Server ist die alleinige Quelle der Wahrheit für "geändert") |
| `raw_calendar_data` | `String` (`@Lob`) | nein | vollständiger roher `calendar-data`-Inhalt dieser Ressource, wie vom Server geliefert |

Primärschlüssel zusammengesetzt aus `(source_calendar_id, href)`, analog zu
`relay_state`/`pending_creation` über `@IdClass`.
`hibernate.ddl-auto: update` legt die Tabelle automatisch an.

### Tabelle `calendar_sync_token`

Neue Entity `CalendarSyncTokenEntity`
(`adapters/outbound/persistence/CalendarSyncTokenEntity.java`) — eine
Ein-Zeile-pro-Kalender-Tabelle, getrennt von `calendar_replica_resource`
gehalten (kein Join beim häufigen Lesepfad `loadSyncToken()` nötig, ein
Full-Resync über `resetTo(...)` schreibt beide Tabellen ohnehin unabhängig):

| Spalte | Typ (Java) | Nullable | Beschreibung |
|---|---|---|---|
| `source_calendar_id` | `String` | nein, PK | wie oben |
| `sync_token` | `String` | ja | `null` bedeutet "kein initialer Sync bisher erfolgreich abgeschlossen" |

### `JpaCalendarReplicaStoreAdapter implements CalendarReplicaStore`

Neue Klasse in `adapters/outbound/persistence/`, folgt strukturell exakt
`JpaPendingCreationQueueAdapter`s Aufbau: Konstruktor nimmt zwei neue,
geteilte Spring-Data-Repositories (`CalendarReplicaResourceJpaRepository`,
`CalendarSyncTokenJpaRepository`) und die pro-Kalender-`sourceCalendarId`
entgegen; jede Methode filtert explizit über diese ID.
`applyDelta(...)` und `resetTo(...)` sind mit `@Transactional` annotiert
(Spring, zulässig in `adapters/*` — verboten ist Spring nur in `core/*`),
damit Ressourcen-Upserts/-Löschungen und der Sync-Token-Update pro Aufruf
atomar bleiben. Genau wie `JpaStateStoreAdapter` und
`JpaPendingCreationQueueAdapter` ist auch dieser Adapter **kein**
auto-gescannter Spring-Singleton-Bean — `RelayWiringConfiguration`
konstruiert eine Instanz pro Kalender von Hand.

```java
JpaCalendarReplicaStoreAdapter(
        CalendarReplicaResourceJpaRepository resourceRepository,
        CalendarSyncTokenJpaRepository tokenRepository,
        String sourceCalendarId)
```

**Wichtige Konsequenz für `PerCalendarComponentBeanDefinitionPruner`
(ADR-006):** ADR-006 hat exakt diesen Fall bereits vorausgesehen ("Die Liste
der zu entfernenden Klassennamen muss von Hand gepflegt werden, wenn künftig
weitere pro-Kalender-parametrisierte Komponenten hinzukommen"). Sowohl
`JpaCalendarReplicaStoreAdapter` als auch — falls `CalDavCalendarSourceAdapter`
nicht ohnehin schon in der Liste steht, was es laut ADR-006 bereits tut —
müssen der `PER_CALENDAR_COMPONENT_CLASS_NAMES`-Liste hinzugefügt werden;
sonst schlägt der Kontext-Start mit derselben
`UnsatisfiedDependencyException` fehl, die ADR-006 für die ursprünglichen
drei Klassen beschreibt.

### Wiring (`RelayWiringConfiguration`)

`CalDavCalendarSourceAdapter`s Konstruktor-Aufruf in `buildUseCase(...)`
bekommt die beiden neuen Argumente: ein pro Kalender frisch konstruiertes
`new JpaCalendarReplicaStoreAdapter(calendarReplicaResourceJpaRepository,
calendarSyncTokenJpaRepository, calendar.id())` (Repositories als geteilte
`@Bean`s, wie `relayStateJpaRepository`/`pendingCreationJpaRepository`) und
`calendar.deltaSyncEnabled()` (siehe Konfiguration unten) — exakt nach
demselben Muster wie der bestehende
`new JpaPendingCreationQueueAdapter(pendingCreationJpaRepository,
calendar.id())`-Aufruf. `buildUseCases(...)`/`pollAndRelaySourceCalendarUseCases(...)`
reichen die beiden neuen Repository-Beans entsprechend durch.

## Konfiguration: `relay.calendars[].delta-sync-enabled`

```java
public record CalendarConfig(
        @NotBlank String id,
        @NotBlank String caldavUrl,
        @NotBlank String caldavUsername,
        @NotBlank String caldavPassword,
        @NotBlank String organizerEmail,
        @NotBlank String attendeeEmail,
        @NotBlank String fromAddress,
        @NotBlank String replyToAddress,
        @DefaultValue("true") boolean deltaSyncEnabled) {
}
```

- **Bewusst ein Pro-Kalender-Feld**, anders als `recurring-event-horizon`
  und `relay.initialization.*`, die beide bewusst global sind (siehe
  "Weitere Entscheidungen" unten für die Begründung des Unterschieds).
- Default `true`: Delta-Sync ist die neue Normalbetriebsart. Ein Server, der
  `sync-collection` nicht unterstützt, wird ohnehin automatisch erkannt und
  fällt selbstständig auf `calendar-query` zurück (siehe "Fallback bei
  fehlender Server-Unterstützung" oben) — `deltaSyncEnabled: false` ist ein
  manueller Notausschalter für den Fall, dass die automatische Erkennung
  selbst nicht wie erwartet funktioniert oder ein konkreter
  Server-spezifischer Bug im neuen Code-Pfad auftritt, nicht der reguläre
  Weg, einen nicht unterstützenden Server zu behandeln.
- Kein zusätzlicher globaler Eintrag in der flachen `RELAY_*`-Umgebungsvariablen-
  Tabelle von `README.md` nötig — wie `id`/`caldav-url`/die Adressfelder ist
  dies ein Feld innerhalb eines `relay.calendars[]`-Eintrags, nicht global
  (siehe bestehendes Muster in `application.yml`s Beispielblock).

`application.yml`s auskommentierter Beispielblock bekommt beim Implementieren
eine zusätzliche Zeile im `calendars`-Eintrags-Beispiel:

```yaml
#   - id: personal-nextcloud
#     caldav-url: https://cloud.example.com/remote.php/dav/calendars/user/personal/
#     ...
#     reply-to-address: ${RELAY_PERSONAL_REPLY_TO_ADDRESS}
#     # delta-sync-enabled: true   -- Default; auf false setzen, um für diesen
#     # einen Kalender dauerhaft auf die vollständige calendar-query-Anfrage
#     # zurückzufallen (z. B. bei einem Server, der sync-collection fehlerhaft
#     # implementiert, aber nicht sauber als nicht unterstützt erkennbar ist).
```

## Fehlerfälle — Ergänzungen

- **`calendarReplicaStore.loadSyncToken()`/`loadAllResources()` schlägt
  fehl.** Wird als `CalendarReplicaStoreException` unverpackt bis
  `readEvents()` durchgereicht, dort in eine `CalDavCalendarSourceException`
  gewrappt (konsistent mit jedem anderen internen Fehlerfall dieses
  Adapters) — der gesamte Poll-Zyklus bricht ab, exakt wie ein
  fehlgeschlagener `calendar-query`-Request heute.
- **`calendarReplicaStore.applyDelta(...)`/`resetTo(...)` schlägt fehl.**
  Gleiche Behandlung. Da beide erst **nach** einer bereits erfolgreich
  empfangenen `sync-collection`-Antwort aufgerufen werden, ist der
  ungünstigste Fall ein Absturz-Fenster, in dem der Server bereits einen
  neuen Token vergeben hat, dieser aber lokal nicht gespeichert wurde — der
  nächste Poll fragt dann erneut mit dem alten Token an und bekommt
  denselben Delta ein zweites Mal (siehe `applyDelta`s Idempotenz-Hinweis
  oben), kein Datenverlust.
- **Der Server antwortet auf eine `sync-collection`-Anfrage mit einem
  Statuscode, der weder `207` noch als "Token ungültig" noch als "Report
  nicht unterstützt" erkennbar ist** (z. B. ein transienter `503 Service
  Unavailable`). Wird wie jeder unerwartete `calendar-query`-Statuscode
  heute behandelt: `CalDavCalendarSourceException`, kein automatischer
  Fallback auf Legacy-`calendar-query` (das wäre bei einem rein transienten
  Fehler falsch — der nächste Poll versucht `sync-collection` erneut mit
  demselben, weiterhin gültigen Token).

## Weitere Entscheidungen — eigene Einschätzung

- **Keine Migration/kein Backfill für bereits laufende Kalender nötig.** Ein
  Kalender, der schon lange vor dieser Feature produktiv lief, hat beim
  Rollout weder einen Sync-Token noch eine `calendar_replica_resource`-Zeile
  — `loadSyncToken()` liefert `null`, der Adapter führt automatisch genau
  einmal den initialen Sync durch (kostet dieselbe Serverlast wie der
  bisherige `calendar-query`-Poll, den dieser Zyklus sonst ohnehin gemacht
  hätte). Der bereits bestehende `RelayState`-Bestand dieses Kalenders ist
  davon komplett unberührt — `RelayDiffPlanner` sieht danach exakt dieselbe
  `SourceEvent`-Liste wie vorher, nur über einen anderen Beschaffungsweg.
  Kein manueller Eingriff, kein Skript, keine Sonderbehandlung nötig.
- **`Depth: 1`-Header zusätzlich zu `<D:sync-level>1</D:sync-level>`
  gesendet, statt sich auf eines von beiden zu verlassen.** RFC 6578 selbst
  verlangt nur `sync-level`; einige real existierende Serverimplementierungen
  prüfen aus Kompatibilitätsgründen mit anderen REPORT-Typen aber zusätzlich
  den `Depth`-Header. Beide zu senden ist strikt konservativer als nur
  einen, kostet nichts, und hält den Request-Aufbau symmetrisch zur
  bestehenden `calendar-query`-Anfrage, die `Depth: 1` bereits setzt.
- **`etag` wird gespeichert, aber vom Adapter selbst nie verglichen —
  rein informativ.** Der Server ist über `sync-collection`s Delta-Antwort
  bereits die alleinige, autoritative Quelle dafür, ob sich eine Ressource
  geändert hat; ein zusätzlicher clientseitiger ETag-Vergleich wäre
  redundant und könnte bei Server-Bugs (ETag ändert sich, ohne dass der
  Inhalt sich ändert, oder umgekehrt) sogar zu falschen Ergebnissen führen,
  wenn er dem Server widerspricht. Der ETag wird dennoch mitgespeichert, weil
  er ohne Zusatzaufwand in derselben Antwort mitkommt und für spätere
  Diagnose (z. B. manuelles Debugging eines Server-seitigen Sync-Bugs)
  nützlich ist, ohne dass dafür eine eigene Config-Fläche oder Logik
  entsteht.
- **`deltaSyncEnabled` ist bewusst ein Pro-Kalender-, nicht ein globales
  Feld** — anders als `recurring-event-horizon` und
  `relay.initialization.*` (beide laut `event-filtering.md`/
  `burst-filter-initialization.md` bewusst global, da sie fachliche
  **Policies** sind, die einheitlich für jeden Kalender gelten sollen).
  Ob `sync-collection` funktioniert, ist dagegen eine **Server-Fähigkeit**,
  die zwischen mehreren konfigurierten Kalendern unterschiedlicher Anbieter
  (z. B. ein selbst gehosteter Nextcloud-Server und ein anderer
  CalDAV-Provider) legitim unterschiedlich ausfallen kann — ein globaler
  Schalter würde einen funktionierenden Kalender unnötig auf den langsameren
  Pfad zwingen, nur weil ein anderer Kalender Probleme macht.
- **Kein proaktiver `PROPFIND`-Fähigkeits-Check
  (`<D:supported-report-set>`) vor dem ersten `sync-collection`-Versuch.**
  Stattdessen reaktive Erkennung: `sync-collection` wird einfach versucht,
  ein nicht erfolgreicher, nicht als "Token ungültig" erkennbarer Statuscode
  löst den dauerhaften Fallback aus (siehe oben). Ein vorgeschalteter
  `PROPFIND`-Check würde in der weit überwiegenden Mehrheit der Fälle
  (Server unterstützt RFC 6578) einen komplett unnötigen zusätzlichen
  Roundtrip pro Prozessstart kosten, für einen Fall, der ohnehin nur einmal
  pro Prozesslebensdauer auftritt und folgenlos mit einem einzigen
  fehlgeschlagenen Versuch behandelt wird.
- **Kein Pruning/keine Größenbegrenzung der lokalen Replik
  (`calendar_replica_resource`).** Die Tabelle wächst mit der Anzahl
  verschiedener `href`s, die der Server je gemeldet hat, und schrumpft nur,
  wenn der Server eine Ressource tatsächlich als gelöscht meldet — bei
  langlebigen Kalendern mit sehr großer Historie könnte das über Jahre eine
  nicht-triviale Zeilenzahl ergeben. Bewusst nicht adressiert: Genau
  dieselbe Größenordnung an Rohdaten wird auch heute bei jedem
  `calendar-query`-Poll komplett (wenn auch nicht dauerhaft) übertragen, die
  lokale Speicherung ist also keine neue Belastung relativ zum Status quo,
  nur eine dauerhafte statt eine transiente. Sollte sich das als reales
  Problem zeigen, ist ein zeitbasiertes Pruning alter, längst vergangener
  Ressourcen ein naheliegender späterer Ausbauschritt, der den
  `CalendarReplicaStore`-Port-Vertrag nicht ändern müsste.

## Out of scope

- **`<D:limit>`-Element zur serverseitigen Pagination sehr großer Deltas**
  (RFC 6578, optional). Nicht gefordert angesichts der bereits in
  `burst-filter-initialization.md` angenommenen typischen Kalendergröße
  (einige Dutzend bis wenige hundert Termine).
- **Proaktiver `PROPFIND`-Fähigkeits-Check vor dem ersten
  `sync-collection`-Versuch.** Siehe "Weitere Entscheidungen" oben —
  bewusst rein reaktive Erkennung.
- **ETag-basierte clientseitige Änderungserkennung als Ergänzung oder Ersatz
  für die serverseitige Delta-Antwort.** Der ETag wird nur informativ
  gespeichert, siehe oben.
- **Pruning/Größenbegrenzung der lokalen Ressourcen-Replik.** Siehe "Weitere
  Entscheidungen" oben — akzeptiertes, unadressiertes Risiko für sehr
  langlebige Kalender.
- **Unterstützung für `calendar-multiget` REPORT als Ergänzung zu
  `sync-collection`** (z. B. falls ein Server bei `sync-collection` nur
  `href`+`getetag`, aber kein `calendar-data` inline liefert, und ein
  Folge-Request pro geändertem `href` nötig wäre). Diese Spec geht davon
  aus, dass der Server `calendar-data` bereits inline in der
  `sync-collection`-Antwort liefert, wenn es im `<D:prop>` angefordert wird
  — siehe Open Questions für die noch offene Verifikation gegen einen
  echten Server.
- **Änderungen an `StateStore`, `PendingCreationQueue`, `BurstBudget` oder
  `CalendarSource`.** Alle vier bleiben unangetastet.
- **Cross-Kalender-geteilter Sync-Token oder geteilte Replik.** Ein
  Sync-Token ist laut RFC 6578 inhärent an genau eine Collection-URL
  gebunden — dasselbe "eine konfigurierte Instanz pro Quellkalender"-Muster
  wie bei jedem anderen Port dieses Projekts, keine Ausnahme.

## Open questions

- **Liefert der tatsächlich eingesetzte CalDAV-Server `calendar-data` inline
  in der `sync-collection`-Antwort, wenn im `<D:prop>` angefordert?** RFC
  6578 selbst schreibt das nicht zwingend vor (der Kern der RFC behandelt
  primär `href`+`getetag`-Änderungserkennung); viele verbreitete
  Implementierungen (u. a. sabre/dav-basierte Server wie Nextcloud/Baikal)
  unterstützen es in der Praxis, aber das ist nicht in dieser Spec
  verifiziert worden, sondern eine Annahme. Muss vor oder während der
  Implementierung gegen den tatsächlichen Produktions-CalDAV-Server geprüft
  werden — falls nicht unterstützt, wäre ein Folge-`calendar-multiget`-Request
  für die geänderten `href`s nötig (siehe "Out of scope" oben), was diese
  Spec dann in einer Folgeiteration ergänzen müsste.
- **Exakter Statuscode/Precondition-Body, den der tatsächlich eingesetzte
  Server für einen ungültigen Sync-Token zurückgibt.** Diese Spec folgt der
  RFC-6578-Vorgabe (`403 Forbidden` mit `<D:valid-sync-token/>`) und
  berücksichtigt zusätzlich `507 Insufficient Storage` als bekannte
  Abweichung einzelner Implementierungen — eine Verifikation gegen den
  echten Server steht noch aus. Reagiert der Server abweichend (z. B. mit
  einem gänzlich anderen Statuscode), müsste die Erkennungslogik in
  `parseSyncCollectionResponse(...)` entsprechend nachgezogen werden, ohne
  dass sich der `CalendarReplicaStore`-Port-Vertrag dafür ändern müsste.
- **Ist ein Pro-Kalender-Notausschalter (`delta-sync-enabled`) tatsächlich
  nötig, oder reicht die automatische Server-Fähigkeits-Erkennung allein?**
  Diese Spec entscheidet sich für den zusätzlichen manuellen Schalter als
  Sicherheitsnetz gegen Bugs im neuen Code-Pfad selbst (nicht nur gegen
  fehlende Serverunterstützung, die bereits automatisch erkannt wird) — siehe
  "Weitere Entscheidungen" oben. Sollte sich in der Praxis zeigen, dass die
  automatische Erkennung allein zuverlässig genug ist, könnte der Schalter
  in einer späteren Aufräum-Iteration wieder entfernt werden.
- **Pruning der lokalen Ressourcen-Replik für sehr langlebige Kalender.**
  Bewusst nicht Teil dieser Spec (siehe "Weitere Entscheidungen"/"Out of
  scope" oben) — bei konkretem Leidensdruck ein späterer, eigenständiger
  Ausbauschritt.
