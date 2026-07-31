# Feature: Replica Retirement — Aufräumen alter `CalendarReplicaStore`- und `RelayState`-Einträge

Kein GitHub-Issue, kein Auftrag aus einer Konversation. Diese Spec ist ein
reiner **Entwurf zum Parken im Repository**: ein durchdachtes Design, falls
das unten beschriebene Wachstumsrisiko irgendwann tatsächlich zum Problem
wird — nicht, weil dafür heute ein nachgewiesener Bedarf besteht. **Status:
entworfen, nicht eingeplant** ("designed, not scheduled"), bewusst anders
formuliert als die beiden bereits abgeschlossenen Einträge unter `CLAUDE.md`s
"Deliberately deferred" (Delta-Sync, Event-Filterung), die beide inzwischen
umgesetzt sind — dieses Feature ist genuin offen, nicht nur noch nicht
begonnen.

## Kontext — warum diese Spec trotz fehlendem akutem Bedarf existiert

`docs/features/delta-sync.md` (PR #24) hat `CalendarReplicaStore` eingeführt:
eine dauerhafte, pro Quellkalender geführte lokale Replik aller jemals vom
CalDAV-Server gemeldeten rohen Ressourcen (`href` → `calendar_data`/`etag`),
plus den zugehörigen `RelayState`-Bestand in `StateStore`, der laut ADR-003
abgesagte Einträge ebenfalls nie löscht. Beide Tabellen wachsen seither
ausschließlich monoton — `calendar_replica_resource` um eine Zeile pro
jemals gesehenem `href`, `relay_state` um eine Zeile pro jemals gesehenem
`sourceUid`, keine von beiden schrumpft je automatisch. `delta-sync.md`s
eigener Abschnitt "Weitere Entscheidungen" hat dieses Wachstum bereits
bewusst unadressiert gelassen und explizit als "ein späterer, eigenständiger
Ausbauschritt" benannt, falls sich das je als reales Problem zeigt.

**In der Praxis ist die Speicherlast heute vernachlässigbar:** Roher
ICS-Text pro Ressource liegt typischerweise im Bereich weniger Kilobyte;
selbst mehrjährige Kalenderhistorien mit hunderten bis niedrigen tausenden
Ressourcen bleiben im niedrigen einstelligen Megabyte-Bereich. Es gibt
aktuell keinen betrieblichen Leidensdruck, der ein Aufräumen rechtfertigt.
Diese Spec existiert trotzdem — als bereit ausgearbeitetes Design, das bei
Bedarf ohne erneute Grundsatzdiskussion umgesetzt werden kann, nicht weil
das Wachstum heute tatsächlich Sorgen bereitet.

## Design-Kernentscheidung — die Gefahr, die dieses gesamte Design abwendet

> **`CalendarSource.readEvents()` rekonstruiert bei jedem Aufruf den
> vollständigen, aktuellen `SourceEvent`-Bestand aus
> `CalendarReplicaStore.loadAllResources()`.** Konkret: In
> `CalDavCalendarSourceAdapter` liest `readAllVEventsViaDeltaSyncOrFallback()`
> bei aktivem Delta-Sync über `toVEvents(loadReplicaResources())` **alle**
> aktuell in der Replik gecachten `href`s (nicht nur die zuletzt gelieferten
> Deltas) und übergibt sie an dieselbe `expandAll(allVEvents, now)`-Pipeline,
> die auch beim Legacy-`calendar-query`-Pfad läuft (siehe
> `docs/features/delta-sync.md`, Abschnitt "Zusammenspiel von
> Ressourcen-Deltas..."). Eine Ressource, deren Zeile in
> `calendar_replica_resource` gelöscht wird, taucht damit ab dem
> **nächsten** `readEvents()`-Aufruf überhaupt nicht mehr in `currentEvents`
> auf — unabhängig davon, ob der CalDAV-Server sie noch führt oder nicht.
>
> `RelayDiffPlanner.plan(...)` behandelt genau dieses Verschwinden als
> Beleg für eine echte Absage: Die zweite Schleife am Ende von `plan(...)`
> (`RelayDiffPlanner.java`, Zeilen 101–110) erzeugt für **jeden** noch
> `active`-en vorherigen `RelayState`, dessen `sourceUid` nicht in
> `seenSourceUids` (also nicht in der aktuellen `currentEvents`-Menge)
> auftaucht, ein `RelayAction.Cancel` — unabhängig davon, *warum* der
> Quelltermin verschwunden ist.
>
> **Die Konsequenz:** Würde eine Zeile aus `calendar_replica_resource`
> naiv gelöscht, nur weil der zugehörige Quelltermin alt ist, während der
> zugehörige `RelayState`-Eintrag noch `active` ist, sähe das für
> `RelayDiffPlanner` exakt so aus, als hätte der Besitzer des Quellkalenders
> diesen Termin gerade gelöscht — der Service würde eine echte
> `METHOD:CANCEL`-iMIP-Mail für einen Termin verschicken, der tatsächlich nie
> abgesagt wurde. **Jedes Retirement-/Pruning-Design darf niemals zulassen,
> dass der `sourceUid` eines noch `active`-en `RelayState` aus dem Snapshot
> verschwindet.** Das ist die eine Invariante, um die dieses gesamte Design
> gebaut ist.

## Feature-Zusammenfassung

Ein neuer, dritter `RelayState`-Lebenszyklus-Zustand — **retired** — plus ein
zweiter, vom gewöhnlichen Poll-and-Relay-Zyklus unabhängiger, periodisch
laufender Wartungsjob lösen die obige Gefahr in zwei getrennten Schritten
auf: Erst wird ein `RelayState`-Eintrag, dessen Quelltermin weit genug in
der Vergangenheit liegt, lokal (ohne jede iMIP-Mail, ohne
`RelayDiffPlanner`) auf `retired` umgestellt; **erst danach**, und nur wenn
jeder aus einem `href` abgeleitete `sourceUid` retired ist (bei
wiederkehrenden Serien zusätzlich nur, wenn die Serie selbst begrenzt und
diese Begrenzung vollständig in der Vergangenheit liegt), darf die
zugehörige `calendar_replica_resource`-Zeile gelöscht werden. Diese
Reihenfolge — erst retiren, dann löschen — ist keine Stilfrage, sondern
direkte Konsequenz der obigen Invariante: Solange ein Eintrag noch `active`
ist, darf sein `href` niemals verschwinden; sobald er `retired` ist, ist er
für `RelayDiffPlanner`s Absage-Erkennung ohnehin unsichtbar (siehe Domain
Model Additions unten), also ist ein späteres Verschwinden seines `href`
folgenlos.

## Akteure

Wie bei jedem bisherigen Feature dieses Projekts: **Scheduler** ist der
einzige Akteur. Anders als bisher gibt es aber **zwei unabhängige,
zeitlich versetzte Scheduler-Trigger** pro Kalender — den bestehenden
Poll-and-Relay-Zyklus (`relay.poll-interval`) und einen neuen, deutlich
selteneren Retirement-Zyklus (siehe Konfiguration unten).

## Use Case: Retire Relay State and Prune Calendar Replica

Ein **neuer, eigenständiger Use Case**, nicht Teil von
`PollAndRelaySourceCalendarUseCase`/`PollAndRelaySourceCalendarService` — die
Konversation, aus der diese Spec entsteht, verlangt ausdrücklich einen
"second, independent scheduled maintenance job (not part of
`PollAndRelaySourceCalendarService`'s poll-and-relay cycle)". Eine eigene
Instanz pro konfiguriertem Quellkalender, exakt wie
`PollAndRelaySourceCalendarUseCase`.

### Vorbedingungen

- Für diese Use-Case-Instanz ist dieselbe `StateStore`-Instanz konfiguriert
  wie für den zugehörigen `PollAndRelaySourceCalendarUseCase` desselben
  Kalenders (geteilte Sicht auf denselben `RelayState`-Bestand).
- Ebenso dieselbe `CalendarSource`- und `CalendarReplicaStore`-Instanz.
- Ein Retention-Zeitraum (`relay.replica-retirement.retention`) und ein
  Job-Intervall (`relay.replica-retirement.interval`) sind konfiguriert
  (siehe Konfiguration unten).

### Ablauf

1. `now = ZonedDateTime.now(clock)`
2. `priorStates = stateStore.loadAll()` — wie beim gewöhnlichen Poll-Zyklus,
   enthält aktive, abgesagte **und** bereits retirete Einträge.
3. **Retirement-Schritt (rein lokale Buchführung, kein Versand):**
   `retirementCandidates = relayRetirementPlanner.planRetirements(priorStates,
   now, replicaRetention)` (neuer, zustandsloser Domänendienst, siehe Domain
   Model Additions unten) liefert jeden `sourceUid`, dessen `RelayState`
   noch `active` ist und dessen `lastKnownEnd` vor
   `now.minus(replicaRetention)` liegt. Für jeden Kandidaten:
   `stateStore.retire(sourceUid)` (neue `StateStore`-Methode, siehe
   Port-Änderungen unten) — **niemals** `RelayDiffPlanner.plan(...)`,
   **niemals** `BlockerSink.send(...)`, **niemals** eine Änderung an
   `sequence`. Ein einzelner fehlgeschlagener `retire(...)`-Aufruf wird
   protokolliert und übersprungen (best-effort, analog zum bestehenden
   `PendingCreationQueue.remove(...)`-Fehlerverhalten in
   `PollAndRelaySourceCalendarService`), ohne die übrigen Kandidaten dieses
   Zyklus abzubrechen.
4. `refreshedStates = stateStore.loadAll()` — erneutes Laden, damit die im
   vorigen Schritt frisch retireten Einträge in der folgenden
   Prüfung bereits berücksichtigt werden.
5. **Pruning-Schritt:**
   `resources = calendarSource.describeCachedResources(now)` (neue
   `CalendarSource`-Methode, siehe Port-Änderungen unten) liefert für jeden
   aktuell in der Replik gecachten `href` die Menge der davon abgeleiteten
   `sourceUid`s sowie ein `structurallyExhausted`-Flag. Ein `href` gilt als
   löschbar, wenn **beide** gelten:
   - `structurallyExhausted` ist `true` (siehe Domain-Model-Additions unten
     für die genaue Bedeutung bei Einzel- vs. wiederkehrenden Terminen), und
   - für **jeden** `sourceUid` in `resource.sourceUids()` gilt: entweder
     existiert in `refreshedStates` ein Eintrag mit `retired() == true`,
     oder es existiert **gar kein** `RelayState`-Eintrag für diesen
     `sourceUid` (z. B. weil er den Erstellungs-Filter nie bestanden hat).
6. Ist die Menge der so ermittelten löschbaren `href`s nicht leer:
   `calendarReplicaStore.deleteResources(prunableHrefs)` (neue
   `CalendarReplicaStore`-Methode, siehe Port-Änderungen unten) — ein
   einziger Aufruf für den gesamten Zyklus, analog zu `applyDelta`s
   Batch-Charakter.
7. Ergebnis: ein neues, einfaches `RelayRetirementCycleResult(int
   retiredCount, int prunedHrefCount, List<RelayFailure> failed)` — kein
   Wiederverwenden von `RelayCycleResult`, da dieser Use Case strukturell
   nichts erstellt, aktualisiert oder abgesagt.

### Fehlerfälle

- **`stateStore.loadAll()` schlägt fehl (Schritt 2 oder 4).** Der gesamte
  Zyklus bricht ab, analog zu einem fehlgeschlagenen `loadAll()` im
  gewöhnlichen Poll-Zyklus — es wurde zu diesem Zeitpunkt noch keine
  Zustandsänderung vorgenommen (Schritt 4s Fehlschlag ist der heiklere Fall,
  siehe "Weitere Entscheidungen" unten für den Umgang mit bereits erfolgten
  `retire(...)`-Aufrufen in diesem Fall).
- **`stateStore.retire(sourceUid)` schlägt für einen einzelnen Kandidaten
  fehl.** Best-effort, siehe Ablaufschritt 3 — protokollieren, überspringen,
  restliche Kandidaten unbeeinflusst weiterverarbeiten. Der übersprungene
  Kandidat wird beim nächsten Retirement-Zyklus erneut versucht (er bleibt
  `active` und damit ein gültiger Kandidat).
- **`calendarSource.describeCachedResources(now)` schlägt fehl.** Bricht nur
  den Pruning-Schritt (5–6) dieses Zyklus ab, **nicht** den bereits
  abgeschlossenen Retirement-Schritt (3) — die in Schritt 3 vorgenommenen
  `retire(...)`-Aufrufe bleiben gültig und wirksam, unabhängig vom Ausgang
  des Pruning-Schritts. Der nächste Zyklus versucht das Pruning erneut.
- **`calendarReplicaStore.deleteResources(prunableHrefs)` schlägt fehl.**
  Wird wie ein fehlgeschlagenes `applyDelta`/`resetTo` behandelt: Es wurde
  vorher nichts Unwiderrufliches getan (die zu löschenden `href`s waren
  bereits vollständig retired, ein erneuter Versuch im nächsten
  Retirement-Zyklus führt zu genau demselben Ergebnis) — der Zyklus meldet
  den Fehler, ohne Schritt 3 rückgängig zu machen.

## Domain model additions

### `RelayState.retired` — neues Feld

`RelayState` bekommt ein zusätzliches `boolean retired`-Feld, additiv neben
dem bestehenden `boolean active` (kein Ersatz, kein Umbau zu einem Enum —
siehe "Weitere Entscheidungen" unten für die Abwägung gegen eine
`RelayLifecycleStatus`-Aufzählung):

```java
public record RelayState(
        String sourceUid,
        String blockerUid,
        long sequence,
        ZonedDateTime lastKnownStart,
        ZonedDateTime lastKnownEnd,
        boolean active,
        boolean lastKnownAllDay,
        boolean lastKnownBusy,
        boolean lastKnownCancelled,
        boolean retired) {

    public RelayState {
        // ... bestehende Prüfungen unverändert ...
        if (retired && active) {
            throw new IllegalArgumentException("a retired RelayState must not be active");
        }
    }
}
```

Die neue Invariante `retired ⇒ !active` ist bewusst im kompakten Konstruktor
erzwungen, nicht nur dokumentiert — sie ist die Grundlage dafür, dass
`RelayDiffPlanner`s bestehende Absage-Schleife (`prior.active() &&
!seenSourceUids.contains(...)`) **ohne jede Codeänderung** bereits korrekt
niemals für einen retireten Eintrag feuert: Ein retireter Eintrag ist per
Konstruktion nie `active`.

### `RelayDiffPlanner` — eine neue Verzweigung, kein neuer Zweig in der Absage-Schleife

**Wichtige Klarstellung, um zwei unterschiedliche Dinge nicht zu vermischen:**
Der Retirement-*Übergang* selbst (aktiv → retired) läuft, wie oben
beschrieben, ausschließlich über `StateStore.retire(...)` und ruft
`RelayDiffPlanner.plan(...)` nie auf. Das schließt aber nicht aus, dass
`plan(...)` selbst angepasst werden muss, um korrekt zu reagieren, **wenn
ein bereits retireter `sourceUid` in einem späteren Poll-Zyklus wieder in
`currentEvents` auftaucht** (siehe unten, "Wiederauftauchen"). Anders als
bei Delta-Sync (`docs/features/delta-sync.md`, das explizit betont "keine
einzige Zeile in `core/app` oder `core/domain`" geändert zu haben), ändert
dieses Feature `RelayDiffPlanner` tatsächlich — strukturell vergleichbar mit
`event-filtering.md`, das `plan(...)`s Entscheidungsoberfläche ebenfalls
erweitert hat.

**Kontrastverhalten für abgesagte (`active = false`, `retired = false`)
Einträge, zur Einordnung (bereits heute so, ADR-003):** Taucht der
`sourceUid` eines zuvor abgesagten Eintrags erneut in `currentEvents` auf,
behandelt `plan(...)` das identisch zu einem geänderten aktiven Zustand
(`!prior.active() || relayStateChanged(...)` ist `true`, da `!prior.active()`
bereits `true` ist) → `RelayAction.Update`, unter Wiederverwendung der
vorhandenen `blockerUid` und Fortsetzung der `sequence`-Zählung
("Wiederauferstehung", siehe ADR-003 und `docs/domain.md`).

**Neues Verhalten für retirete (`active = false`, `retired = true`)
Einträge:** Genau dieser bestehende Zweig (`!prior.active() ||
relayStateChanged(...)`) würde einen retireten Eintrag ohne Anpassung
**identisch** zu einem abgesagten behandeln — also erneut eine
`REQUEST`-Einladung verschicken, sobald sein `sourceUid` wieder sichtbar
wird (z. B. weil ein erzwungener Full-Resync — siehe `delta-sync.md`s
"Erzwungener Full-Resync bei ungültigem Token" — ein noch vom Server
geführtes `href` erneut in die Replik lädt). Das wäre fachlich falsch: Eine
Einladung für einen Termin, der bereits stattgefunden hat und dessen
Buchführung absichtlich als endgültig erledigt markiert wurde, ergibt keinen
Sinn. `plan(...)` braucht daher eine neue, vorgeschaltete Verzweigung:

```java
for (var event : currentEvents) {
    seenSourceUids.add(event.sourceUid());
    var prior = priorByUid.get(event.sourceUid());
    if (prior == null) {
        // ... unverändert: Erstellungs-Filter ...
    } else if (prior.retired()) {
        // NEU: retiret bleibt retiret. Kein Versand, keine RelayState-Änderung,
        // kein Log-würdiges Ereignis -- ein stiller No-op.
    } else if (!prior.active() || relayStateChanged(event, prior)) {
        // ... unverändert: Update inkl. Wiederauferstehung ...
    }
}
```

Die bestehende Absage-Schleife am Ende von `plan(...)` bleibt **unverändert**
— wie oben hergeleitet, filtert `prior.active()` retirete Einträge dort
bereits allein über die neue Konstruktor-Invariante korrekt heraus, ohne
dass die Schleife selbst etwas von `retired` wissen muss.

### `RelayRetirementPlanner` — neuer, zustandsloser Domänendienst

Strukturell analog zu `RelayDiffPlanner`: reine Funktion ihrer Eingaben,
kein I/O, kein Port-Wissen.

```java
@DomainService
public final class RelayRetirementPlanner {

    public List<String> planRetirements(
            List<RelayState> priorStates, ZonedDateTime now, Period replicaRetention) {
        return priorStates.stream()
                .filter(RelayState::active)
                .filter(state -> state.lastKnownEnd().isBefore(now.minus(replicaRetention)))
                .map(RelayState::sourceUid)
                .toList();
    }
}
```

`retired()`-Einträge werden implizit nie zurückgegeben, da sie laut der
neuen Konstruktor-Invariante nie `active()` sind — kein expliziter
`!state.retired()`-Filter nötig. Bewusst **kein** Methode auf
`RelayDiffPlanner` selbst (anders als `isPastCreationCutoff`, das dort schon
sitzt) — Retirement ist fachlich ein komplett anderer Entscheidungsprozess
(zeitbasiertes Aufräumen, kein Diff gegen einen aktuellen Snapshot) und
verdient einen eigenen, kleinen Dienst statt `RelayDiffPlanner`s ohnehin
schon dichte Verantwortung weiter zu vergrößern.

## Port-Änderungen

### `StateStore` — neue Methode `retire(String sourceUid)`

```java
public interface StateStore {

    List<RelayState> loadAll();

    void save(RelayState state);

    void markCancelled(String sourceUid, long sequence);

    /**
     * Marks a still-active RelayState as retired: {@code active} becomes {@code false},
     * {@code retired} becomes {@code true}. {@code blockerUid}, {@code sequence}, and every
     * {@code lastKnown*} field stay unchanged -- no iMIP message was sent for this
     * transition, unlike {@link #markCancelled}. Deliberately not routed through
     * {@code markCancelled} itself: conflating the two would make a retired entry
     * indistinguishable from a cancelled one to any caller that only inspects {@code
     * active}, exactly the ambiguity {@link RelayState#retired()} exists to remove.
     * Never deletes the row -- same "keep bookkeeping forever" rationale as ADR-003 for
     * cancelled entries.
     *
     * @throws StateStoreException if the underlying persistence operation fails, or if no
     *     entry exists for {@code sourceUid}
     */
    void retire(String sourceUid);
}
```

Vorbedingung/Fehlerverhalten analog zu `markCancelled`:
`JpaStateStoreAdapter.retire(...)` wirft (analog zur bestehenden
`markCancelled`-Implementierung, die intern `IllegalStateException` wirft,
wenn keine Zeile existiert) eine Ausnahme, wenn für `sourceUid` keine Zeile
existiert — dieser Fall ist strukturell ausgeschlossen, solange nur
`RelayRetirementPlanner`-Kandidaten (die per Definition aus einem bereits
geladenen `RelayState` stammen) übergeben werden.

### `CalendarSource` — neue Methode `describeCachedResources(ZonedDateTime now)`

**Das ist der eigentlich schwierige Teil dieses Designs.** `sync-collection`
und `calendar_replica_resource` sind, wie `delta-sync.md` ausführlich
herleitet, pro **`href`** organisiert — bei einer wiederkehrenden Serie ist
das die **gesamte** Serie (Master-`VEVENT` plus alle
`RECURRENCE-ID`-Overrides) in einer einzigen Ressource. `RelayState` ist
dagegen pro **`sourceUid`** organisiert, und für ein Vorkommen aus einer
Serie ist `sourceUid` ein zusammengesetzter Schlüssel
(`<Serien-UID>#<ursprünglicher Vorkommen-Instant>`, siehe `docs/domain.md`).
Ein einzelner `href` kann also viele `sourceUid`s tragen — Schritt 5 des
Ablaufs oben braucht für jeden `href` sowohl die vollständige Menge der
davon abgeleiteten `sourceUid`s als auch die Antwort auf "kann dieser
`href` je wieder einen bislang unbekannten `sourceUid` hervorbringen".

Diese zweite Frage lässt sich **nicht** durch einen einfachen
Datumsvergleich auf der rohen Ressource beantworten — sie braucht dieselbe
`RRULE`/`EXDATE`/`RECURRENCE-ID`-Expansionslogik, die
`CalDavCalendarSourceAdapter` bereits für `readEvents()` verwendet
(`expandAll(List<VEvent>, ZonedDateTime)` und `expandSeries(String,
List<VEvent>, ZonedDateTime)`, beides private Methoden derselben Klasse,
kein separater Dienst). Der Grund: Ein Einzeltermin (kein `RRULE`) ist
strukturell immer "erschöpft" — er hat genau ein Vorkommen, das nie ein
weiteres, bislang unbekanntes Vorkommen nachliefern kann. Eine
wiederkehrende Serie **ohne** `UNTIL`/`COUNT` in ihrem `RRULE` ist dagegen
**niemals** erschöpft, unabhängig davon, wie alt ihr erstes Vorkommen ist —
sie liefert bei jedem `readEvents()`-Aufruf neue Vorkommen nach, sobald das
nach vorne gleitende `recurringEventHorizon`-Zeitfenster weiter voranschreitet
(exakt der Mechanismus, den `delta-sync.md`s Abschnitt "Warum die rohe
Ablage nicht bereits expandierte Vorkommen speichert" beschreibt). Nur eine
Serie **mit** `UNTIL`/`COUNT`, deren letztes mögliches Vorkommen bereits
vollständig in der Vergangenheit liegt, ist tatsächlich erschöpft — dafür
muss die vom Master-`VEVENT` bereits geparste `Recur<ZonedDateTime>`-Instanz
befragt werden (`net.fortuna.ical4j.model.Recur`, dieselbe Klasse, die
`expandRecurringSeries(...)` bereits für `recur.getDates(...)` konstruiert;
die genaue Methode zur `UNTIL`/`COUNT`-Abfrage auf der konkret gepinnten
ical4j-Version ist während der Implementierung zu verifizieren, siehe Open
Questions).

Vorgeschlagener Port-Zuschnitt — bewusst als Erweiterung von `CalendarSource`
selbst, nicht als neuer, dedizierter Port (siehe "Weitere Entscheidungen"
unten für die Begründung):

```java
public interface CalendarSource {

    List<SourceEvent> readEvents();

    /**
     * For every raw resource currently cached in this calendar's {@code
     * CalendarReplicaStore}, describes which {@code sourceUid}s it currently derives and
     * whether it can structurally ever derive a not-yet-seen {@code sourceUid} again,
     * evaluated against {@code now}. Read-only; touches neither {@code CalendarReplicaStore}
     * nor {@code StateStore}.
     */
    List<CachedResourceOccurrences> describeCachedResources(ZonedDateTime now);
}
```

```java
/**
 * @param href the WebDAV resource identity, as in {@link CachedCalendarResource#href()}
 * @param sourceUids every {@code sourceUid} this resource currently derives -- exactly one
 *     entry for a non-recurring VEVENT, one per currently expanded occurrence for a
 *     recurring series
 * @param structurallyExhausted {@code true} for a non-recurring VEVENT (a single occurrence
 *     can never grow a second one), or for a recurring series whose {@code RRULE} carries
 *     {@code UNTIL}/{@code COUNT} and whose last possible occurrence has already ended
 *     before {@code now}. {@code false} for an unbounded recurring series, regardless of
 *     how old its first occurrence is -- it can always still reveal a new occurrence as the
 *     recurring-event horizon slides forward on a future {@code readEvents()} call.
 */
public record CachedResourceOccurrences(String href, Set<String> sourceUids, boolean structurallyExhausted) {}
```

`describeCachedResources(...)` reicht dieselbe Gruppierungs- und
Expansionslogik durch, die `readEvents()` intern ohnehin schon für jeden
`href` durchläuft — sie liefert lediglich zusätzlich das `href`, statt es
nach der Gruppierung nach `UID` zu verwerfen, sowie das neue
`structurallyExhausted`-Flag. Die eigentliche Kreuzung "ist jeder dieser
`sourceUid`s retired (oder nie getrackt)" bleibt bewusst Aufgabe der
Anwendungsschicht (Ablaufschritt 5 oben), nicht dieser Methode — sie kennt
`StateStore` nicht und soll es auch nicht kennen müssen.

### `CalendarReplicaStore` — neue Methode `deleteResources(List<String> hrefs)`

```java
public interface CalendarReplicaStore {

    // ... loadSyncToken(), loadAllResources(), applyDelta(...), resetTo(...) unverändert ...

    /**
     * Permanently removes the cached raw resource for every {@code href} in {@code hrefs},
     * without touching the stored sync-token. Deliberately distinct from {@link
     * #applyDelta}'s {@code removedHrefs} handling, which always advances the sync-token to
     * reflect a real, server-reported deletion -- this method exists purely for local
     * retention pruning of resources the server has <em>not</em> reported as removed at
     * all. Conflating the two would make a future forced full resync (see {@link #resetTo})
     * incorrectly believe the server had already told this replica about these removals.
     *
     * @throws CalendarReplicaStoreException if the underlying persistence operation fails
     */
    void deleteResources(List<String> hrefs);
}
```

Die explizite Trennung von `applyDelta`s `removedHrefs` ist hier keine
Formalie: Würde stattdessen `applyDelta(currentToken, List.of(), hrefsToDelete)`
wiederverwendet, würde der lokal gespeicherte Sync-Token unverändert
bleiben (was für sich harmlos wäre), aber die Semantik der Methode selbst
würde verwischen — ein künftiger Leser des Codes könnte fälschlich
annehmen, jeder über `applyDelta` entfernte `href` sei eine
server-gemeldete Löschung gewesen. Eine eigene, klar benannte Methode hält
diese beiden fachlich unterschiedlichen Lösch-Gründe (Server meldet
Löschung vs. lokale Aufbewahrungsfrist abgelaufen) auch im Code
unterscheidbar.

## Persistenz

### `relay_state` — neue Spalte `retired`

`RelayStateEntity` bekommt eine zusätzliche Spalte, analog zum bestehenden
Muster (`lastKnownAllDay`/`lastKnownBusy`/`lastKnownCancelled` wurden in
`event-filtering.md` genau so additiv ergänzt):

| Spalte | Typ (Java) | Nullable | Beschreibung |
|---|---|---|---|
| `retired` | `boolean` | nein, Default `false` | wie `RelayState.retired()` |

`hibernate.ddl-auto: update` legt die neue Spalte automatisch mit Default
`false` für jede bestehende Zeile an — keine manuelle Migration nötig,
exakt wie bei den drei `last_known_*`-Spalten aus Issue #3.
`JpaStateStoreAdapter.retire(...)` folgt strukturell `markCancelled(...)`:
Zeile per `sourceCalendarId`+`sourceUid` laden, `active = false`,
`retired = true` setzen, speichern.

### `calendar_replica_resource` — keine Schemaänderung

`deleteResources(...)` braucht kein neues Feld — ein einfaches
`resourceRepository.deleteBySourceCalendarIdAndHrefIn(sourceCalendarId,
hrefs)` (dieselbe Repository-Methode, die `applyDelta(...)` bereits für
seine eigene `removedHrefs`-Behandlung verwendet, siehe
`JpaCalendarReplicaStoreAdapter.applyDelta(...)`) genügt, nur ohne den
begleitenden `saveSyncToken(...)`-Aufruf.

## Konfiguration

```java
public record RelayProperties(
        @NotNull Duration pollInterval,
        @Valid List<CalendarConfig> calendars,
        @NotNull @DefaultValue("P6M") Period recurringEventHorizon,
        @NotNull @Valid InitializationProperties initialization,
        @NotNull @Valid ReplicaRetirementProperties replicaRetirement) {

    public record ReplicaRetirementProperties(
            @NotNull @DefaultValue("P1M") Period retention,
            @NotNull @DefaultValue("P1D") Duration interval) {
    }
}
```

- **`relay.replica-retirement.retention`** (`Period`, Vorschlag-Default
  `P1M`): Wie weit `lastKnownEnd` in der Vergangenheit liegen muss, damit
  ein noch aktiver `RelayState`-Eintrag retirement-fähig wird. `Period`,
  nicht `Duration` — dieselbe Begründung wie bei
  `recurringEventHorizon`: "ein Monat" ist ein kalendarisches, kein
  Elapsed-Time-Maß.
- **`relay.replica-retirement.interval`** (`Duration`, Vorschlag-Default
  `P1D`): Wie oft der neue Wartungsjob pro Kalender läuft. Bewusst deutlich
  seltener als `relay.poll-interval` — Retirement ist keine
  zeitkritische Fachlichkeit, ein täglicher Lauf genügt bei weitem.
  `Duration`, nicht `Period`: reine Elapsed-Time-Angabe wie
  `initialization.burst-interval`.
- **Bewusst unter `relay.replica-retirement.*` verschachtelt statt als
  flaches `relay.replica-retention`**, wie ursprünglich in der Konversation
  skizziert — konsistent mit dem bereits etablierten Muster für
  `relay.initialization.*` (zwei zusammengehörige Werte einer Feature
  bekommen eine gemeinsame verschachtelte Gruppe statt zweier unabhängiger
  Top-Level-Felder). Eine reine Umbenennung/Restrukturierung der Skizze,
  keine inhaltliche Abweichung.
- Beide Werte sind, wie `recurringEventHorizon` und `initialization.*`,
  **global**, nicht pro `CalendarConfig` überschreibbar — dieselbe
  Begründung wie dort: es sind fachliche Policies, keine
  Server-Fähigkeiten wie `deltaSyncEnabled`.
- Analog zum bestehenden defensiven Muster im kompakten Konstruktor von
  `RelayProperties` (siehe `initialization`) baut der kompakte Konstruktor
  bei fehlender `relay.replica-retirement`-Sektion defensiv eine
  `ReplicaRetirementProperties`-Instanz mit beiden Default-Werten.

## Weitere Entscheidungen — eigene Einschätzung

- **Additives `boolean retired`-Feld statt Umbau von `active` in ein
  `RelayLifecycleStatus`-Enum (`ACTIVE`/`CANCELLED`/`RETIRED`).** Ein Enum
  wäre die "sauberere" Modellierung eines echten Drei-Zustands-Lebenszyklus
  und wurde erwogen. Dagegen entschieden: Es hätte jede bestehende
  Konstruktor-Aufrufstelle von `RelayState` (`PollAndRelaySourceCalendarService`,
  `JpaStateStoreAdapter`, alle Tests) sowie die Spaltenbedeutung von
  `relay_state.active` brechend geändert, für einen Zustand, der bislang nur
  binär war. Ein zusätzliches, additives `boolean retired`-Feld mit der
  Invariante `retired ⇒ !active` erreicht dieselbe Unterscheidbarkeit mit
  einer rein additiven Änderung — genau das bereits etablierte Muster, mit
  dem `event-filtering.md` `lastKnownAllDay`/`lastKnownBusy`/
  `lastKnownCancelled` zu `RelayState` hinzugefügt hat, statt es
  umzumodellieren. Die Ambiguität "boolesches Feld statt sauberem
  Enum" wird als vertretbarer Kompromiss akzeptiert; siehe Open Questions.
- **`CalendarSource.describeCachedResources(...)` statt eines neuen,
  dedizierten Ports.** Erwogen wurde ein eigener Port (analog zur
  Begründung, mit der `CalendarReplicaStore` und `PendingCreationQueue`
  jeweils eigene Ports statt Erweiterungen bestehender bekamen, ADR-008).
  Hier dagegen entschieden: Die Fähigkeit, "aus rohem `calendar-data`
  abzuleiten, welche `sourceUid`s ein `href` trägt und ob seine `RRULE`
  begrenzt und ausgeschöpft ist", ist **untrennbar** an dieselbe
  CalDAV-/`RRULE`-Expansionslogik gebunden, die ausschließlich
  `CalDavCalendarSourceAdapter` besitzt und die `readEvents()` bereits
  heute exklusiv nutzt — es gibt keine zweite, unabhängige Implementierung
  dieses Wissens, die ein separater Port sinnvoll kapseln würde. Ein neuer
  Port hätte nur eine zweite Schnittstelle für exakt denselben, bereits
  einzigen Adapter erzwungen, ohne echten Trennungsgewinn. `CalendarSource`
  ist bereits der Port, hinter dem jedes CalDAV-Protokollwissen dieses
  Adapters für die Anwendungsschicht verborgen liegt (siehe `delta-sync.md`)
  — eine zweite Methode desselben Ports fügt sich dort nahtlos ein.
- **`describeCachedResources(...)` liegt bewusst auf `CalendarSource`, nicht
  auf `CalendarReplicaStore`.** `CalendarReplicaStore` ist laut
  `delta-sync.md` bewusst ein "dummer", CalDAV-unwissender
  Persistenz-Port (rohe Bytes, `href`, ETag, Sync-Token) — er kennt weder
  `VEVENT`, noch `RRULE`, noch `SourceEvent`. Die Expansionslogik, die diese
  neue Methode braucht, gehört fachlich exakt dorthin, wo `expandAll`/
  `expandSeries` bereits leben.
- **Retirement-Übergang läuft nie durch `RelayDiffPlanner`, aber
  `RelayDiffPlanner` selbst wird trotzdem angepasst.** Das ist kein
  Widerspruch, sondern zwei getrennte Fragen: Ob eine bestehende
  `active`-Zeile retiret wird, entscheidet ausschließlich
  `RelayRetirementPlanner` plus `StateStore.retire(...)`, ohne
  `RelayDiffPlanner` je zu befragen. Ob ein **bereits** retireter Eintrag,
  der wieder sichtbar wird, ignoriert oder wiederbelebt wird, ist dagegen
  exakt die Frage, die `RelayDiffPlanner.plan(...)` für jeden `sourceUid`
  mit vorhandenem `RelayState` sowieso schon beantwortet — ein neuer,
  zusätzlicher Fall in einer bereits bestehenden Fallunterscheidung, keine
  neue Aufrufkette.
- **Zwei getrennte `loadAll()`-Aufrufe im Ablauf (Schritt 2 und 4) statt
  eines gemeinsamen, im Prozess aktualisierten Zustands.** Einfacher und
  konsistent mit dem bereits etablierten Muster in
  `PollAndRelaySourceCalendarService.pollAndRelay()`, das `priorStates`
  ebenfalls als unveränderliche Momentaufnahme behandelt und nie In-Place
  mutiert. Kostet einen zusätzlichen `StateStore`-Lesezugriff pro
  Retirement-Zyklus — bei einem Tagesintervall vernachlässigbar.
- **Kein Löschen von `RelayState`-Zeilen, auch nicht für retirete
  Einträge, auch nicht Jahre später.** Konsistent mit ADR-003s Begründung
  für abgesagte Einträge: Die Buchführung "vergisst" einen Quelltermin nie
  von sich aus. Nur `calendar_replica_resource` (die reine
  CalDAV-Protokoll-Replik, kein fachliches Gedächtnis) wird tatsächlich
  geprunt.

## Out of scope

- **Jede Änderung am Sync-Token-Mechanismus selbst.** `loadSyncToken()`,
  `applyDelta(...)`, `resetTo(...)` bleiben unverändert; `sync-collection`s
  Korrektheit wird von diesem Feature nicht berührt.
- **Löschen von `RelayState`-Zeilen.** Siehe "Weitere Entscheidungen" oben
  — bewusst nie, auch nicht für retirete Einträge.
- **Konfigurierbarkeit pro Kalender** für `retention`/`interval`. Beide
  bleiben global, wie `recurringEventHorizon`.
- **Ein ADR für dieses Feature.** Laut Auftrag folgt ein ADR erst, falls
  dieses Design tatsächlich umgesetzt wird — nicht Teil dieser Spec.
- **Code, Tests, Migrationen.** Diese Spec ist reiner Entwurf; kein
  Coder-Agent wird für dieses Feature beauftragt, solange es "designed, not
  scheduled" bleibt.

## Open questions

- **Exakter Name/Zuschnitt des dritten Lebenszyklus-Zustands.** Diese Spec
  entscheidet sich für ein additives `RelayState.retired`-Boolean (siehe
  "Weitere Entscheidungen"). Ein sauberes `RelayLifecycleStatus`-Enum
  (`ACTIVE`/`CANCELLED`/`RETIRED`) wäre die fachlich präzisere Modellierung
  eines echten Drei-Zustands-Lebenszyklus und sollte bei tatsächlicher
  Umsetzung erneut gegen den dann aktuellen Code-Stand abgewogen werden,
  falls sich `RelayState` bis dahin ohnehin ändert.
- **Exakter Default für `relay.replica-retirement.retention`.** `P1M` ist
  ein plausibler, aber nicht weiter hergeleiteter Vorschlagswert — abhängig
  davon, ab wann ein Quelltermin fachlich "nicht mehr relevant" ist
  (typische Umbuchungs-/Storno-Fristen im privaten Kalenderkontext wurden
  für diese Spec nicht recherchiert).
- **Exakter Default für `relay.replica-retirement.interval`.** `P1D`
  (täglich) ist ebenfalls ein plausibler, nicht weiter hergeleiteter
  Vorschlagswert.
- **Exakte ical4j-API zur `UNTIL`/`COUNT`-Abfrage auf `Recur<ZonedDateTime>`
  auf der aktuell gepinnten ical4j-Version.** Diese Spec geht davon aus,
  dass `Recur` diese Information irgendeiner Form nach abfragbar bereitstellt
  (grundsätzlich Teil von RFC 5545s `RECUR`-Werttyp), hat die exakte
  Methode aber nicht gegen den tatsächlichen `net.fortuna.ical4j`-Import
  dieses Projekts verifiziert — muss vor der Implementierung von
  `structurallyExhausted` geklärt werden.
- **Verhalten bei einem Absturz zwischen Ablaufschritt 3 (Retirement) und
  Schritt 6 (Pruning).** Diese Spec nimmt an, dass ein solcher Absturz
  folgenlos ist (bereits retirete Einträge bleiben retiret, der nächste
  Retirement-Zyklus setzt beim Pruning einfach erneut an) — sollte aber bei
  der Implementierung nochmals gegen den tatsächlichen
  `RelayRetirementCycleResult`-Rückgabepfad verifiziert werden.
- **Ob `describeCachedResources(...)`s `Set<String> sourceUids`
  möglicherweise zu groß für sehr lange, unbegrenzte Serien wird** (jede
  bislang expandierte Vorkommen-`sourceUid`, nicht nur die retirement-fähigen)
  — für eine unbegrenzte Serie ohnehin irrelevant, da `structurallyExhausted`
  für sie immer `false` ist und ihr `href` nie zur Löschung infrage kommt;
  für eine sehr lange **begrenzte** Serie (`COUNT` sehr groß) potenziell ein
  spürbarer, aber nicht als Problem verifizierter Speicher-Overhead pro
  Retirement-Zyklus.
