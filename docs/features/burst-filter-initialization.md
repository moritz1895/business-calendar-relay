# Feature: Burst-Filter für Erstinitialisierung (Anti-Spam-Schutz fürs Business-Postfach)

GitHub-Issue #16 — "Burst-Filter für Erstinitialisierung (Anti-Spam-Schutz
fürs Business-Postfach)". Diese Spec erweitert `docs/features/event-filtering.md`
(Erstellungs-Filter, Issue #3) um eine zeitliche Entzerrung der Erstanlage:
Der Erstellungs-Filter entscheidet weiterhin **ob** ein Quelltermin
erstellungsberechtigt ist, diese Feature entscheidet zusätzlich **wann**
eine bereits erstellungsberechtigte Erstanlage tatsächlich verschickt wird.
Sie ändert nichts an `isEligibleForCreation` selbst und nichts an der
Behandlung von Aktualisierungen oder Absagen.

## Feature-Zusammenfassung

Wird ein Quellkalender zum ersten Mal angebunden (oder ein weiterer Kalender
später hinzugefügt), liefert der bestehende Erstellungs-Filter (Issue #3)
zwar keine vergangenen Termine mehr, aber jeden zukünftigen Einzeltermin und
jedes Vorkommen einer wiederkehrenden Serie innerhalb des
`recurring-event-horizon` **im selben Poll-Zyklus** als Erstellung. Bei einer
gewachsenen Kalenderhistorie können das hunderte iMIP-Mails in Sekunden sein
— das Risiko, dass der Mailserver des Business-Postfachs den Absender als
Spam-Quelle einstuft oder sperrt. Diese Feature führt einen **pro
Quellkalender geführten Initialisierungs-Modus** ein: Die vollständige
Erstanlage-Liste, die `RelayDiffPlanner.plan(...)` auf einem jungfräulichen
`StateStore` ohnehin in einem Rutsch berechnet, wird einmalig eingesammelt,
persistiert und danach über mehrere Poll-Zyklen hinweg scheibchenweise
abgearbeitet — begrenzt durch ein konfigurierbares, **postfachweites**
Sendebudget (Default: 5 Erstanlagen pro Stunde, über alle konfigurierten
Kalender zusammengerechnet). Sobald ein Kalender seine Rückstands-Warteschlange
vollständig abgearbeitet hat, kippt er dauerhaft in den normalen
Poll-and-Diff-Betrieb von heute, ohne jede weitere Verhaltensänderung.

## Wichtigste Regel — darf bei der Umsetzung nicht verloren gehen

> **Der Initialisierungs-Modus ist ein reines, einmaliges Capture-und-Drain-
> Verfahren für genau die eine Erstanlage-Liste, die `RelayDiffPlanner.plan(...)`
> beim allerersten Poll-Zyklus eines Kalenders (leerer `StateStore`) berechnet.
> `plan(...)` wird für einen noch nicht vollständig initialisierten Kalender
> **kein zweites Mal** aufgerufen, solange seine Warteschlange noch Einträge
> enthält — es gibt zu jedem Zeitpunkt höchstens eine aktive
> Rückstands-Warteschlange pro Kalender, niemals zwei sich überlappende. Der
> gewöhnliche Poll-and-Diff-Zyklus (`readEvents()` + `plan(...)` gegen den
> vollständigen `currentEvents`-Stand) läuft für einen Kalender exakt dann
> wieder an, wenn seine Warteschlange leer ist — und exakt dann automatisch,
> ohne dass dafür ein eigenes, persistiertes "initialisiert"-Flag gesetzt
> werden muss (Herleitung siehe "Weitere Entscheidungen" unten). Ein Fehler
> an dieser Stelle — etwa `plan(...)` während des Drainings erneut gegen den
> aktuellen Kalenderstand aufzurufen — würde eine zweite, überlappende
> Erstanlage-Menge erzeugen und den gesamten Zweck dieser Feature
> unterlaufen: die Drossel selbst würde dann erneut unbegrenzt viele
> Erstanlagen auf einen Schlag einreihen.**

Ebenso wichtig: Die Warteschlange trägt ausschließlich Erstanlagen
(`RelayAction.Create`). Ein Quelltermin, der bereits einen `RelayState`-
Eintrag besitzt — ob aktiv oder bereits abgesagt — wird **niemals** in die
Warteschlange aufgenommen und niemals durch sie beeinflusst. Das
postfachweite Sendebudget dieser Feature gilt ausschließlich für das
Draining der Initialisierungs-Warteschlange; Aktualisierungen und Absagen
(egal ob während oder nach der Initialisierungsphase eines Kalenders) sowie
jede Erstanlage nach abgeschlossener Initialisierung durchlaufen weiterhin
exakt den heutigen, ungedrosselten Pfad.

## Akteure

Unverändert gegenüber `relay-orchestration.md` und `event-filtering.md`:
**Scheduler** ist der einzige Akteur; es gibt keinen menschlichen Trigger für
einen einzelnen Poll-Zyklus.

## Use Case: Poll and Relay Source Calendar — Änderungen am Ablauf

Command, Vorbedingungen (bis auf die unten ergänzten) und das Ergebnis
(`RelayCycleResult`) bleiben unverändert. Die Änderung betrifft ausschließlich
den internen Ablauf von `PollAndRelaySourceCalendarService.pollAndRelay()`.

**Zusätzliche Vorbedingungen:**

- Für diese Use-Case-Instanz ist eine `PendingCreationQueue`-Instanz
  konfiguriert, gescoped auf denselben Quellkalender wie ihre `StateStore`-
  Instanz (siehe Port-Änderungen unten).
- Eine einzige, **über alle Use-Case-Instanzen geteilte** `BurstBudget`-
  Instanz ist konfiguriert (siehe Port-Änderungen unten).
- Ein Burst-Budget (`burst-size`, `burst-interval`) ist konfiguriert — global
  für alle Quellkalender, siehe Konfiguration unten.

### Neuer Ablauf von `pollAndRelay()`

**Bisher (`relay-orchestration.md`/`event-filtering.md`):**

> 1. `currentEvents = calendarSource.readEvents()`
> 2. `priorStates = stateStore.loadAll()`
> 3. `actions = planner.plan(currentEvents, priorStates, now, recurringEventHorizon)`
> 4. Jede Aktion verarbeiten (`processCreate`/`processUpdate`/`processCancel`).

**Neu:**

> 1. `priorStates = stateStore.loadAll()`
> 2. `pendingQueue = pendingCreationQueue.loadAllOrderedByStart()` — die
>    persistierte Rückstands-Warteschlange dieses Kalenders, aufsteigend nach
>    `start` sortiert (siehe Port-Änderungen unten für die genaue Garantie).
> 3. **Erst-Capture, nur wenn `pendingQueue` und `priorStates` beide leer
>    sind** (siehe "Weitere Entscheidungen" unten für die Herleitung, warum
>    genau diese Kombination "noch nie initialisiert" bedeutet):
>    - `currentEvents = calendarSource.readEvents()`
>    - `now = ZonedDateTime.now(clock)`
>    - `actions = planner.plan(currentEvents, priorStates = [], now, recurringEventHorizon)`
>      — da `priorStates` hier per Definition leer ist, kann `plan(...)`
>      strukturell **ausschließlich** `RelayAction.Create`-Einträge liefern,
>      nie `Update` oder `Cancel` (siehe `RelayDiffPlanner`, Zweig `if (prior
>      == null)` ist der einzige erreichbare Zweig).
>    - `actions` wird aufsteigend nach `start` sortiert und über
>      `pendingCreationQueue.saveAll(sortedActions)` **komplett und in einem
>      Rutsch** persistiert, bevor irgendetwas versendet wird.
>    - `pendingQueue = sortedActions` für den Rest dieses Zyklus.
> 4. **Solange `pendingQueue` nicht leer ist**, wird ausschließlich gedraint
>    (siehe "Draining der Warteschlange" unten) — der gewöhnliche
>    `readEvents()`/`plan(...)`-Zyklus wird für diesen Kalender in diesem
>    Zyklus **nicht** ausgeführt. Der Zyklus endet danach mit dem
>    `RelayCycleResult` des Drainings.
> 5. **Ist `pendingQueue` leer** (entweder weil in Schritt 3 nichts
>    erstellungsberechtigt war, oder weil eine zuvor nicht leere Warteschlange
>    inzwischen vollständig abgearbeitet wurde), läuft **exakt der heutige,
>    unveränderte** Ablauf: `currentEvents = calendarSource.readEvents()`,
>    `actions = planner.plan(currentEvents, priorStates, now, recurringEventHorizon)`,
>    Verarbeitung wie in `event-filtering.md` beschrieben. Ab hier gibt es
>    keinerlei Unterschied mehr zum heutigen Verhalten — dieser Kalender ist
>    ab jetzt dauerhaft im "initialisierten" Zustand, ohne dass das irgendwo
>    explizit vermerkt wird.

Schritt 3 und Schritt 5 rufen `planner.plan(...)` bewusst **nie im selben
Zyklus** auf — Schritt 3 läuft nur, wenn beide Quellen leer sind, Schritt 5
nur, wenn `pendingQueue` (nach optionalem Schritt 3) leer ist. Es gibt also
pro Zyklus höchstens einen `plan(...)`-Aufruf, exakt wie heute.

### Draining der Warteschlange

Für jeden Eintrag `item` aus `pendingQueue`, **in aufsteigender `start`-
Reihenfolge** (Vorgabe 5 aus der Konversation zu Issue #16, minimiert die
Zahl der Einträge, die während eines mehrtägigen Drainings noch veralten
können):

1. **Bereits anderweitig verarbeitet?** Existiert bereits ein `RelayState`
   für `item.sourceUid()` in `priorStates`? Dann wurde dieser Eintrag in
   einem früheren, durch einen Neustart unterbrochenen Draining-Durchlauf
   bereits erfolgreich versendet und gespeichert, aber seine
   Warteschlangen-Zeile wurde (noch) nicht entfernt (siehe
   "Restart-Sicherheit" unten für das genaue Warum). → Zeile aus
   `pendingCreationQueue` entfernen, kein erneuter Versand, weiter mit dem
   nächsten Eintrag.
2. **Veraltet?** `planner.isPastCreationCutoff(item.start(), now)` (neue
   Methode auf `RelayDiffPlanner`, siehe Domain-Model-Additions unten)? Ist
   der Start inzwischen in die Vergangenheit gerückt, seit der Eintrag beim
   Capture erfasst wurde → Zeile aus `pendingCreationQueue` entfernen, **kein
   Versand, kein `RelayState`** (Vorgabe 4 aus der Konversation: veraltete
   Einträge werden verworfen, nicht erzwungen nachgeholt). Weiter mit dem
   nächsten Eintrag.
3. **Budget verfügbar?** `burstBudget.tryAcquireSendSlot()`. Liefert dieser
   Aufruf `false` (postfachweites Budget für das aktuelle Zeitfenster
   ausgeschöpft — von diesem oder einem anderen Kalender), wird das Draining
   für **diesen gesamten Zyklus sofort abgebrochen**; alle noch nicht
   betrachteten Einträge bleiben unverändert in der Warteschlange und werden
   im nächsten Zyklus erneut versucht, ganz oben beginnend (aufsteigende
   `start`-Reihenfolge bleibt dadurch über Zyklusgrenzen hinweg erhalten).
4. **Senden.** Liefert `tryAcquireSendSlot()` `true`, wird `item` — das
   strukturell bereits ein vollwertiges `RelayAction.Create` ist (siehe
   Domain-Model-Additions unten) — durch die **unveränderte**, bestehende
   private Methode `processCreate(RelayAction.Create, List<String> created,
   List<RelayFailure> failed)` geschickt: gleiches Rendering
   (`ImipCalendarRenderer.renderRequest`), gleicher `BlockerSink.send`-Aufruf,
   gleiches `StateStore.save` bei Erfolg, gleiche Fehlerisolation
   (`trySend`/`trySaveState`) bei Misserfolg. Es gibt an dieser Stelle
   **keinen zweiten, parallelen Sende-Code-Pfad**.
   - Bei Erfolg (Eintrag landet in `created`): Zeile aus
     `pendingCreationQueue` entfernen.
   - Bei Misserfolg (Eintrag landet in `failed`, `StateStore` unverändert):
     Zeile bleibt in `pendingCreationQueue` stehen und wird im nächsten
     Zyklus erneut versucht — exakt dieselbe Retry-Semantik, die
     `event-filtering.md`/`relay-orchestration.md` bereits für gewöhnliche
     Erstanlagen beschreiben.
   - Der zuvor verbrauchte Budget-Slot wird bei Misserfolg **nicht**
     zurückgegeben (siehe "Weitere Entscheidungen" unten).
   - Danach weiter mit dem nächsten Eintrag (Schritt 3 erneut, solange noch
     Einträge übrig sind).

Das Draining eines einzelnen Zyklus kann also, wenn das Budget es hergibt,
mehrere Einträge auf einmal versenden (bis zu `burst-size`, falls im
aktuellen Zeitfenster noch keine andere Kalenderinstanz Slots verbraucht
hat) — die eigentliche Drosselung liegt vollständig in `BurstBudget`, nicht
in einer zusätzlichen Pro-Zyklus-Obergrenze dieser Methode.

### Restart-Sicherheit — der genaue Mechanismus

Die Warteschlange wird in Schritt 3 vollständig persistiert, **bevor**
irgendetwas versendet wird — ein Absturz vor dem ersten Versand verliert
also nichts: Beim nächsten Start wird `pendingQueue` aus der Datenbank
exakt so wieder geladen, wie sie zuletzt gespeichert war.

Die eigentliche Restart-Sicherheit während des Drainings selbst hängt an
zwei bereits vorhandenen Fakten aus der jüngsten Bugfix-PR
(`fix/deterministic-blocker-uid`):

- `blockerUid` wird für eine Erstanlage **deterministisch** aus `sourceUid`
  abgeleitet (`RelayDiffPlanner.deriveBlockerUid`, `UUID.nameUUIDFromBytes`),
  nicht mehr zufällig.
- Jeder erfolgreiche `processCreate`-Durchlauf speichert seinen `RelayState`
  über `StateStore.save`, **bevor** die Methode zum Aufrufer zurückkehrt.

Daraus folgt: Sobald `item.sourceUid()` einen `RelayState`-Eintrag hat, ist
dieser Eintrag garantiert vollständig und mit genau dem `blockerUid` versehen,
den ein erneuter Versand ohnehin berechnen würde — ein tatsächliches
Doppelt-Verschicken kann es also gar nicht geben, selbst wenn Schritt 1 des
Drainings (die `RelayState`-Existenzprüfung) aus irgendeinem Grund übersprungen
würde. Die Prüfung existiert trotzdem als **primärer** Mechanismus (nicht nur
als Absicherung), weil sie zusätzlich das unnötige erneute Senden einer
bereits zugestellten iMIP-Mail verhindert, das ein reiner
`blockerUid`-Determinismus allein nicht verhindern würde (Outlook würde eine
identische `REQUEST`-Mail bei gleicher `SEQUENCE` zwar folgenlos ignorieren,
aber sie würde trotzdem unnötig gegen das Postfach und das Sendebudget
zählen).

Der konkrete Absturz-Fall, den dieser Mechanismus abdeckt: Prozess stirbt
**zwischen** erfolgreichem `StateStore.save(...)` in `processCreate` und dem
anschließenden `pendingCreationQueue`-Zeilen-Entfernen (zwei getrennte
Schreiboperationen, keine gemeinsame Transaktion über zwei verschiedene
Adapter hinweg). Nach dem Neustart lädt Schritt 1 des Drainings `priorStates`
frisch, findet den bereits vorhandenen `RelayState` für dieses `sourceUid`,
und entfernt die verwaiste Warteschlangen-Zeile nachträglich, ohne erneut zu
senden. Dieser Mechanismus wäre **vor** der Determinismus-Bugfix-PR nicht
sicher möglich gewesen: Ein Resend hätte damals eine neue, zufällige
`blockerUid` erzeugt und damit einen zweiten, unabhängigen Blocker in Outlook
angelegt, statt den bereits gesendeten wiederzuerkennen.

### Fehlerfälle — Ergänzungen

- **`pendingCreationQueue.loadAllOrderedByStart()` schlägt fehl.** Gleiches
  Verhalten wie ein fehlgeschlagenes `StateStore.loadAll()`: Es wurde in
  diesem Zyklus noch nichts versendet, der Aufruf bricht komplett ab und die
  Ausnahme wird unverändert an `PollAndRelaySchedulerAdapter` weitergereicht
  (dort generisch als "Poll cycle aborted unexpectedly" geloggt).
- **`pendingCreationQueue.saveAll(...)` beim Erst-Capture schlägt fehl.**
  Gleiches Verhalten: Es wurde noch keine einzige Erstanlage aus dieser
  Berechnung versendet, voller Abbruch ist sicher. Der nächste Poll-Zyklus
  sieht wieder `pendingQueue` und `priorStates` beide leer vor und
  wiederholt das Capture unverändert — `plan(...)` liefert deterministisch
  dieselbe (oder, falls der Kalender sich zwischenzeitlich geändert hat, eine
  aktualisierte) Erstanlage-Liste.
- **`pendingCreationQueue.remove(sourceUid)` schlägt nach erfolgreichem
  Versand/Speichern fehl.** Wird bewusst **nicht** gesondert behandelt und
  darf die laufende Verarbeitung der übrigen Warteschlangen-Einträge nicht
  abbrechen (anders als ein fehlgeschlagenes `StateStore.save`/
  `markCancelled`, das laut `use-cases.md` den gesamten Zyklus abbricht) —
  der `RelayState`-Existenzcheck in Draining-Schritt 1 macht eine
  liegengebliebene Zeile beim nächsten Zyklus ohnehin folgenlos. Der
  Coder-Agent implementiert `remove(...)`-Aufrufe im Draining daher als
  best-effort (loggen, nicht werfen lassen), analog zu einer
  "aufräumenden", nicht fachlich entscheidenden Operation.
- **`burstBudget.tryAcquireSendSlot()` wirft eine Ausnahme.** Wird nicht
  erwartet (reine In-Memory-Arithmetik, siehe Port-Änderungen) und deshalb
  bewusst **nicht** durch eine eigene Ausnahmeklasse oder Fehlerisolation
  abgesichert — eine hier geworfene `RuntimeException` verhält sich wie jede
  andere unerwartete Ausnahme in `pollAndRelay()` und bricht den Zyklus
  vollständig ab.

## Domain model additions

### `RelayDiffPlanner.isPastCreationCutoff(ZonedDateTime start, ZonedDateTime now)` — neue öffentliche Methode

```java
public boolean isPastCreationCutoff(ZonedDateTime start, ZonedDateTime now) {
    return start.isBefore(now);
}
```

Extrahiert exakt Bedingung 1 aus `isEligibleForCreation` (den
Vergangenheits-Cutoff) als eigenständige, von außen aufrufbare Prüfung.
`isEligibleForCreation` wird intern auf diese Methode umgestellt (`if
(isPastCreationCutoff(event.start(), now)) { return false; }`), damit die
Regel an genau einer Stelle im Code steht — eine risikolose, rein
strukturelle Refaktorierung ohne Verhaltensänderung an `isEligibleForCreation`
selbst.

Bewusst wird **keine** der übrigen vier Bedingungen aus
`isEligibleForCreation` (ganztägig, beschäftigt, storniert,
Wiederholungs-Zeitfenster) als weitere wiederverwendbare Methode extrahiert
oder beim Draining erneut geprüft — siehe "Weitere Entscheidungen" unten für
die Begründung, warum das für Warteschlangen-Einträge weder nötig noch
sinnvoll ist. Die Konversation zu Issue #16 spricht an dieser Stelle
ausdrücklich nur vom "Vergangenheits-Cutoff aus `isEligibleForCreation`",
nicht vom vollständigen Filter.

### `RelayAction.Create` wird als Warteschlangen-Element wiederverwendet — bewusst kein neuer Domain-Typ

Ein Warteschlangen-Eintrag braucht exakt die Felder, die `RelayAction.Create`
bereits trägt: `sourceUid`, `blockerUid`, `sequence` (für einen
Warteschlangen-Eintrag immer `0`, strukturell garantiert, da nur Erstanlagen
je in die Warteschlange gelangen), `start`, `end`, `allDay`, `busy`,
`cancelled`. Es wird bewusst **kein** eigener `PendingCreate`-Typ eingeführt
— das wäre exakt der in `event-filtering.md` bereits abgelehnte Fall eines
"zweiten parallelen Typs ohne fachlichen Vorteil, nur einer zusätzlichen
Umwandlung". `List<RelayAction.Create>` ist damit sowohl das Ergebnis von
`RelayDiffPlanner.plan(...)` beim Capture als auch die Transportform, die
`PendingCreationQueue` persistiert, lädt und an `processCreate` zurückgibt —
konsistent mit Vorgabe 2 aus der Konversation zu Issue #16 ("keine zweite
parallele Sende-Pipeline").

## Port-Änderungen

### `PendingCreationQueue` (neuer, dedizierter Outbound-Port)

**Entscheidung:** `StateStore`s Methodensignaturen (`loadAll()`, `save(...)`,
`markCancelled(...)`) bleiben **vollständig unverändert**. Die
Rückstands-Warteschlange bekommt einen eigenen, dedizierten Port, statt
`StateStore`s Vertrag zu erweitern oder `RelayState`/`relay_state` um einen
dritten Lebenszyklus-Zustand ("noch nicht einmal erstellt") zu ergänzen.
Begründung: `RelayState`s Invarianten (`lastKnownStart`/`lastKnownEnd` nicht
null, `active` bedeutet "existiert und nicht storniert") passen fachlich
nicht zu "existiert noch gar nicht als Blocker, wartet nur auf seinen
Sendezeitpunkt" — das wäre eine Verwässerung eines bereits klar geschnittenen
Wertobjekts für einen fachlich andersartigen Zustand. Ein eigener Port mit
eigener Tabelle hält beide Konzepte sauber getrennt und lässt `StateStore`
sowie jede bestehende `StateStore`-Implementierung unangetastet.

```java
package ms.rohde.businesscalendarrelay.ports.outbound;

@InfrastructureServicePort
public interface PendingCreationQueue {

    List<RelayAction.Create> loadAllOrderedByStart();

    void saveAll(List<RelayAction.Create> pendingCreates);

    void remove(String sourceUid);
}
```

- **Ein konfiguriertes Instanz pro Quellkalender**, exakt wie `StateStore`
  und `CalendarSource` — `loadAllOrderedByStart()` liefert ausschließlich
  Einträge des eigenen Kalenders, aufsteigend nach `start` sortiert (die
  Sortierung ist Teil des Vertrags, nicht Aufgabe des Aufrufers).
- `saveAll(...)` ersetzt **nicht** vorhandene Einträge, sondern wird laut
  dieser Spec ausschließlich einmalig beim Erst-Capture aufgerufen, wenn die
  Warteschlange dieses Kalenders nachweislich leer ist (siehe Ablauf oben) —
  ein Aufruf mit bereits vorhandenen Zeilen für denselben Kalender ist kein
  vorgesehener Anwendungsfall dieser Feature und muss vom Coder-Agenten nicht
  als Merge/Upsert behandelt werden (reines Insert genügt).
- `remove(sourceUid)` ist idempotent: Entfernen eines nicht (mehr)
  vorhandenen Eintrags ist kein Fehler (No-op), da Draining-Schritt 1 aus
  genau diesem Grund robust gegen doppeltes Entfernen sein muss.
- Analog zu `StateStoreException` wirft `saveAll(...)` und `remove(...)` bei
  einem Persistenzfehler eine neue `PendingCreationQueueException extends
  RuntimeException` (zwei Konstruktoren, identisch zu `StateStoreException`).
  `loadAllOrderedByStart()` wirft unverpackt (siehe Fehlerfälle oben, gleiches
  Muster wie `StateStore.loadAll()`).

### `BurstBudget` (neuer, geteilter Outbound-Port)

**Entscheidung zu Vorgabe 6 der Konversation** ("Mechanismus explizit
entwerfen, ohne dass eine Kalender-Instanz eine andere direkt referenziert"):
Ein neuer, **geteilter** Outbound-Port, konzeptionell analog zu `BlockerSink`
— dessen Javadoc bereits heute festhält: *"One configured instance, shared
across all source calendars"*. `BurstBudget` folgt demselben, bereits
etablierten Muster: **eine einzige** Bean-Instanz, die
`RelayWiringConfiguration` einmal baut und in **jede**
`PollAndRelaySourceCalendarService`-Instanz als zusätzlichen
Konstruktorparameter injiziert. Keine Instanz kennt eine andere Instanz —
alle kennen nur denselben geteilten `BurstBudget`.

```java
package ms.rohde.businesscalendarrelay.ports.outbound;

@InfrastructureServicePort
public interface BurstBudget {

    boolean tryAcquireSendSlot();
}
```

`tryAcquireSendSlot()` ist eine reine, thread-sichere In-Memory-Entscheidung
(kein I/O, siehe Adapter unten) und deklariert deshalb bewusst **keine**
eigene Checked- oder Runtime-Exception im Vertrag — anders als `StateStore`
und `PendingCreationQueue`, die echte Persistenz kapseln.

### Adapter: `InMemoryBurstBudgetAdapter`

Neues Paket `adapters/outbound/throttling`. Implementiert ein
Fixed-Window-Zählwerk:

```java
@InfrastructureServiceAdapter
public final class InMemoryBurstBudgetAdapter implements BurstBudget {

    private final Clock clock;
    private final int burstSize;
    private final Duration burstInterval;

    private Instant windowStart;
    private int sentInWindow;

    // Konstruktor speichert clock/burstSize/burstInterval, initialisiert
    // windowStart auf clock.instant() und sentInWindow auf 0.

    @Override
    public synchronized boolean tryAcquireSendSlot() {
        var now = clock.instant();
        if (!now.isBefore(windowStart.plus(burstInterval))) {
            windowStart = now;
            sentInWindow = 0;
        }
        if (sentInWindow >= burstSize) {
            return false;
        }
        sentInWindow++;
        return true;
    }
}
```

`synchronized` ist hier ausreichend und angemessen: `tryAcquireSendSlot()`
wird höchstens `burstSize`-mal pro `burstInterval` über alle Kalender
zusammen aufgerufen (Default: 5-mal pro Stunde) — die Kontention ist damit
strukturell vernachlässigbar, ein lock-freier Mechanismus (`AtomicInteger`
mit CAS-Schleife) wäre unnötige Komplexität für dieses Zugriffsmuster.
Konstruiert wird genau eine Instanz in `RelayWiringConfiguration`, mit dem
bereits vorhandenen geteilten `relayClock`-Bean.

**Bewusst kein zweiter, calendar-übergreifender persistenter Zähler** (siehe
"Weitere Entscheidungen" unten für die vollständige Abwägung) — der Zustand
lebt ausschließlich im Prozessspeicher und wird bei einem Neustart auf ein
frisches Zeitfenster zurückgesetzt.

### Persistenz: `pending_creation`-Tabelle

Neue Entity `PendingCreationEntity`
(`adapters/outbound/persistence/PendingCreationEntity.java`), strukturell
ein direktes Geschwister von `RelayStateEntity`:

| Spalte | Typ (Java) | Nullable | Beschreibung |
|---|---|---|---|
| `source_calendar_id` | `String` | nein, Teil des zusammengesetzten PK | wie bei `relay_state` |
| `source_uid` | `String` | nein, Teil des zusammengesetzten PK | wie bei `relay_state` |
| `blocker_uid` | `String` | nein | die bereits deterministisch abgeleitete `blockerUid` dieser künftigen Erstanlage |
| `start` | `String` (via `ZonedDateTimeStringConverter`) | nein | wie `last_known_start` bei `relay_state`, gleicher Converter aus Konsistenzgründen |
| `end` | `String` (via `ZonedDateTimeStringConverter`) | nein | analog |
| `all_day` | `boolean` | nein | vom auslösenden `SourceEvent` zum Capture-Zeitpunkt |
| `busy` | `boolean` | nein | analog |
| `cancelled` | `boolean` | nein | analog |

Primärschlüssel zusammengesetzt aus `(source_calendar_id, source_uid)`,
analog zu `relay_state` über `@IdClass(PendingCreationEntityId.class)`. Kein
`sequence`- oder `active`-Feld — beides ist für diese Tabelle bedeutungslos
(`sequence` ist immer `0`, "aktiv" hat hier keine eigene Bedeutung, ein
Eintrag existiert entweder in der Tabelle — dann ausstehend — oder nicht
mehr). `hibernate.ddl-auto: update` legt die Tabelle automatisch an, keine
manuelle Migration nötig, exakt wie bei den drei `last_known_*`-Spalten aus
Issue #3.

`JpaPendingCreationQueueAdapter implements PendingCreationQueue` (neue Klasse
in `adapters/outbound/persistence/`) folgt strukturell exakt
`JpaStateStoreAdapter`s Aufbau: Konstruktor nimmt ein
`PendingCreationJpaRepository` (neues Spring-Data-Repository-Interface, von
allen Kalender-Instanzen geteilt, analog `RelayStateJpaRepository`) und die
`sourceCalendarId` entgegen; jede Methode filtert explizit über diese ID.
`findAllBySourceCalendarIdOrderByStartAsc(String)` liefert die für
`loadAllOrderedByStart()` geforderte Sortierung direkt über die
Query-Methode, ohne dass der Adapter selbst sortieren muss. Genau wie
`JpaStateStoreAdapter` ist auch dieser Adapter **kein** auto-gescannter
Spring-Singleton-Bean (Konstruktor braucht die pro-Kalender-`sourceCalendarId`)
— `RelayWiringConfiguration` konstruiert eine Instanz pro Kalender von Hand,
und `PerCalendarComponentBeanDefinitionPruner` entfernt die dafür überflüssige,
nicht konstruierbare `@ArchComponentScan`-Bean-Definition, exakt wie bereits
für `JpaStateStoreAdapter` beschrieben (`docs/technical/database.md`,
`docs/technical/scheduling.md`).

### `CalendarSource`, `BlockerSink`, `StateStore` (bestehende Ports)

Alle drei bleiben **vollständig unverändert** — weder Methodensignaturen noch
transportierte Datenformen wachsen durch diese Feature. `StateStore` wird von
Draining-Schritt 1 ausschließlich lesend über die bereits vorhandene
`loadAll()`-Methode befragt.

### `PollAndRelaySourceCalendarUseCase` (inbound)

Unverändert (`pollAndRelay()` bleibt parameterlos). `PendingCreationQueue`
und `BurstBudget` sind — analog zu `recurringEventHorizon` in
`event-filtering.md` — zusätzliche Konfigurationswerte der Use-Case-Instanz
(Konstruktorargumente von `PollAndRelaySourceCalendarService`), kein neuer
Command-Parameter.

### Wiring (`RelayWiringConfiguration`)

`PollAndRelaySourceCalendarService`s Konstruktor bekommt zwei zusätzliche
Parameter: `PendingCreationQueue pendingCreationQueue` (pro Kalender neu
konstruiert, wie `stateStore`) und `BurstBudget burstBudget` (eine einzige,
geteilte Instanz, wie `blockerSink`). `buildUseCase(...)` und
`buildUseCases(...)` reichen beide entsprechend durch; ein neuer
`@Bean`-Factory-Methode `relayBurstBudget(Clock relayClock, RelayProperties
relayProperties)` baut die eine geteilte `InMemoryBurstBudgetAdapter`-Instanz,
und `buildUseCase(...)` konstruiert zusätzlich pro Kalender ein
`new JpaPendingCreationQueueAdapter(pendingCreationJpaRepository, calendar.id())`,
exakt nach demselben Muster wie der bestehende
`new JpaStateStoreAdapter(relayStateJpaRepository, calendar.id())`-Aufruf.

## Konfiguration: `relay.initialization.burst-size` und `relay.initialization.burst-interval`

Neue, verschachtelte Konfigurationsgruppe `relay.initialization`, analog zu
`relay.calendars[]`s eigenem verschachtelten Record, aber — wie
`recurring-event-horizon` und `poll-interval` — als **einziger, globaler Wert
für alle konfigurierten Quellkalender**, kein Feld unter `relay.calendars[]`
(Vorgabe 6 der Konversation macht das ohnehin zwingend: Das Budget ist
postfachweit, ein Pro-Kalender-Override widerspräche dem fachlichen Zweck
unmittelbar).

```java
public record RelayProperties(
        @NotNull Duration pollInterval,
        @Valid List<CalendarConfig> calendars,
        @NotNull @DefaultValue("P6M") Period recurringEventHorizon,
        @NotNull @Valid InitializationProperties initialization) {

    public record InitializationProperties(
            @Min(1) @DefaultValue("5") int burstSize,
            @NotNull @DefaultValue("PT1H") Duration burstInterval) {
    }
}
```

- **`burst-size`** (`int`, Default `5`): Wie viele Erstanlagen pro
  `burst-interval` postfachweit maximal verschickt werden dürfen. Ein `int`
  genügt (keine `Period`/`Duration`-Semantik nötig, reine Zählgröße);
  `@Min(1)` verhindert eine versehentliche Konfiguration, die überhaupt nie
  eine Erstanlage zuließe und eine Initialisierung damit für immer blockieren
  würde.
- **`burst-interval`** (`java.time.Duration`, Default `PT1H`): Die Fenstergröße
  des Sendebudgets. **`Duration`, nicht `Period`** — anders als
  `recurring-event-horizon` (das bewusst `Period` ist, da "6 Monate ab jetzt"
  ein kalendarisches, kein elapsed-time-Maß ist, siehe `event-filtering.md`),
  ist "einmal pro Stunde" hier eine reine, unzweideutige Elapsed-Time-Angabe:
  Es gibt keine kalendarische Mehrdeutigkeit bei "eine Stunde", unabhängig von
  Zeitzone oder Sommerzeitumstellung — genau die Unterscheidung, die
  `event-filtering.md`s Konfigurationsabschnitt für `recurring-event-horizon`
  bereits explizit macht. Spring Boot bindet `java.time.Duration` bereits
  nativ aus Strings wie `PT1H`, `1h` oder `3600s` (ISO-8601-Duration- oder
  Kurzform-Syntax) — kein eigener Converter nötig.
- Beide Werte sind, wie `recurring-event-horizon`, **global**, nicht pro
  `CalendarConfig` überschreibbar (siehe Out-of-scope unten).
- Analog zum bestehenden Muster für `calendars` im kompakten Konstruktor von
  `RelayProperties` (`calendars = calendars == null ? List.of() : ...`) baut
  der kompakte Konstruktor bei fehlender `relay.initialization`-Sektion in
  der Konfiguration defensiv eine `InitializationProperties`-Instanz mit
  beiden Default-Werten (`new InitializationProperties(5,
  Duration.ofHours(1))`), damit ein vollständig fehlender Abschnitt (wie
  heute schon für `recurring-event-horizon` üblich, siehe die auskommentierte
  Beispielzeile in `application.yml`) nicht zu einem Bindungsfehler führt.

`application.yml` bekommt einen auskommentierten Beispielblock nach demselben
Muster wie `recurring-event-horizon`:

```yaml
relay:
  # `initialization.burst-size`/`initialization.burst-interval` drosseln
  # ausschließlich die einmalige Erstinitialisierung eines Kalenders (Issue
  # #16) -- postfachweit über alle konfigurierten Kalender zusammen, nicht
  # pro Kalender. Defaults: 5 Erstanlagen pro Stunde.
  # initialization:
  #   burst-size: ${RELAY_INITIALIZATION_BURST_SIZE:5}
  #   burst-interval: ${RELAY_INITIALIZATION_BURST_INTERVAL:PT1H}
```

`README.md`s Konfigurationstabelle bekommt beim Implementieren zwei
zusätzliche Zeilen (`RELAY_INITIALIZATION_BURST_SIZE`,
`RELAY_INITIALIZATION_BURST_INTERVAL`), analog zu
`RELAY_RECURRING_EVENT_HORIZON` — das ist Aufgabe des Coder-Agenten bzw. eines
nachfolgenden `docs: sync`-Commits, nicht dieser Spec.

## Weitere Entscheidungen — eigene Einschätzung

Wie schon in `event-filtering.md` gewünscht, hier die Bewertung der
technischen Fragen, die die Konversation zu Issue #16 offen ließ, jeweils mit
Entscheidung und Begründung:

- **Kein eigenes, persistiertes "initialisiert"-Flag pro Kalender.** Der
  initialisierte Zustand ist vollständig aus zwei bereits vorhandenen
  Datenquellen ableitbar: `pendingQueue.isEmpty() && priorStates.isEmpty()`
  bedeutet "noch nie initialisiert, Capture nötig"; sobald irgendeine
  Erstanlage einmal erfolgreich war, ist `priorStates` nie wieder leer
  (abgesagte `RelayState`-Einträge werden nie gelöscht, siehe `domain.md`) —
  ein einmal begonnenes Draining kann diese Bedingung also nie wieder
  fälschlich erfüllen. Ist die Warteschlange (aus welchem Grund auch immer:
  vollständig versendet, oder jeder verbleibende Eintrag beim Draining als
  veraltet verworfen) irgendwann leer, während `priorStates` nicht leer ist,
  läuft automatisch Schritt 5 (der gewöhnliche Zyklus) — ganz ohne
  Fallunterscheidung. Der einzige verbleibende Grenzfall — ein Kalender ohne
  jemals ein einziges erstellungsberechtigtes Event (`pendingQueue` bleibt
  für immer leer, `priorStates` bleibt für immer leer) — wiederholt bei
  jedem Zyklus lediglich harmlos das Capture (`plan(...)` mit leerem
  `priorStates`), was exakt denselben `readEvents()`/`plan(...)`-Aufwand hat
  wie der gewöhnliche Zyklus ohnehin hätte; es entsteht kein Unterschied im
  Verhalten, keine Endlosschleife, kein zusätzlicher Zustand, der inkonsistent
  werden könnte. Ein explizites Flag würde denselben Effekt nur mit
  zusätzlicher Schema- und Synchronisationsfläche erkaufen.
- **Kein Live-Reread des Quellkalenders während des Drainings — nur der
  Vergangenheits-Cutoff wird pro Eintrag neu geprüft.** Die Konversation zu
  Issue #16 benennt explizit nur den "Vergangenheits-Cutoff aus
  `isEligibleForCreation`" als erneut zu prüfende Bedingung, nicht den
  vollständigen Filter. Ganztägig-/Beschäftigt-/Storniert-Status wurden
  bereits beim Capture aus dem damaligen `SourceEvent`-Stand geprüft und
  fließen unverändert in den gespeicherten `RelayAction.Create`-Eintrag ein;
  sie könnten sich am Quellkalender zwischenzeitlich ändern, aber ein
  erneutes Prüfen würde einen zweiten `readEvents()`-Aufruf während des
  Drainings erfordern — exakt die "zweite parallele Pipeline", die Vorgabe 2
  ausdrücklich ausschließt. **Akzeptierte Konsequenz:** Wird ein
  Quelltermin während der Drain-Phase am Quellkalender storniert oder auf
  "nicht beschäftigt" umgestellt, aber sein `start` liegt noch in der
  Zukunft, wird er trotzdem noch als Erstanlage verschickt, sobald er an der
  Reihe ist — sein tatsächlicher Stand wird erst wieder sichtbar, sobald der
  Kalender vollständig initialisiert ist und der gewöhnliche Zyklus (Schritt
  5) wieder frische `SourceEvent`s liest. Das ist ein eng begrenztes Risiko
  (nur während der einmaligen Erstinitialisierung eines Kalenders, nicht im
  Dauerbetrieb) und exakt das, was die Konversation mit ihrer engen
  Formulierung bereits vorgibt.
- **Neue Termine, die während der Drain-Phase am Quellkalender hinzukommen,
  werden erst nach vollständigem Draining erkannt.** Direkte Folge der
  vorigen Entscheidung: Solange `pendingQueue` nicht leer ist, wird
  `readEvents()` gar nicht aufgerufen, ein neu hinzugekommener Termin also
  erst gesehen, sobald Schritt 5 (gewöhnlicher Zyklus) wieder anläuft. Das
  ist akzeptabel, weil (a) das Drain-Fenster durch das Burst-Budget zeitlich
  begrenzt ist (bei Default-Werten und einem typischen Bestand von einigen
  Dutzend bis wenigen hundert Terminen im Bereich von Stunden bis
  niedrigen zweistelligen Tagen) und (b) genau dieses Verhalten am
  einfachsten mit "es gibt zu jedem Zeitpunkt höchstens eine aktive
  Rückstands-Warteschlange pro Kalender" (siehe "wichtigste Regel" oben)
  vereinbar ist. Eine inkrementelle Zusammenführung neu erkannter Termine in
  eine bereits laufende Warteschlange wäre zusätzliche Komplexität für einen
  Effekt, der sich nach Abschluss der Initialisierung ohnehin von selbst
  auflöst.
- **Budget-Slot wird auch bei fehlgeschlagenem Versand verbraucht, nicht
  zurückgegeben.** Der SMTP-Versandversuch selbst ist die Last, vor der das
  Postfach/der Mailserver geschützt werden soll — ob er am Ende erfolgreich
  zugestellt wird oder mit einer Ausnahme fehlschlägt, ändert nichts daran,
  dass eine Verbindung zum SMTP-Server aufgebaut und eine Nachricht
  übertragen wurde. Ein fehlgeschlagener Eintrag bleibt ohnehin in der
  Warteschlange und wird beim nächsten Zyklus erneut versucht, dann mit
  einem neu erworbenen Slot — kein Sonderfall nötig.
- **In-Memory statt persistentem, geteiltem Zähler für `BurstBudget`.**
  Ein Prozessneustart setzt das aktuelle Zeitfenster zurück, statt den bereits
  in diesem Fenster verbrauchten Stand mitzunehmen — bei einer sehr
  unglücklichen Abfolge wiederholter Neustarts innerhalb desselben
  `burst-interval` könnte das Budget in Summe leicht überschritten werden.
  Bewusst akzeptiert: Diese Feature ist ein weicher Anti-Spam-Schutz, keine
  harte Zustellgarantie oder Compliance-Anforderung; ein persistenter,
  nebenläufigkeitssicherer Zähler über mehrere, potenziell gleichzeitig
  laufende Kalender-Instanzen hinweg (geteilte Tabelle mit atomarem
  Increment, oder eine In-Prozess-Sperre mit expliziter Transaktion) wäre
  spürbar mehr Implementierungsaufwand für ein Risiko, das nur bei
  wiederholten Absturz-/Neustart-Zyklen innerhalb eines einzigen
  Ein-Stunden-Fensters überhaupt zum Tragen kommt. Sollte sich das in der
  Praxis als reales Problem zeigen, ist ein Wechsel auf einen persistenten,
  gemeinsam genutzten Zähler (z. B. eine einzelne Zeile in einer neuen
  Tabelle, atomar per `UPDATE ... WHERE` inkrementiert) der naheliegende
  spätere Ausbauschritt, ohne dass sich der `BurstBudget`-Port-Vertrag dafür
  ändern müsste.
- **Kein neues `RelayCycleResult`-Feld für "noch ausstehende
  Warteschlangen-Einträge" oder "als veraltet verworfene Einträge".**
  Konsistent mit der bereits in `event-filtering.md` getroffenen Entscheidung,
  keinen "Katalog übersprungener Termine" zu führen: Ein beim Draining als
  veraltet verworfener Eintrag ist kein Fehler und taucht deshalb in keiner
  der vier bestehenden `RelayCycleResult`-Listen auf, exakt wie ein
  Quelltermin, der den gewöhnlichen Erstellungs-Filter nicht besteht.
  Lediglich ein `LOG.debug`/`LOG.info`-Statement in
  `PollAndRelaySourceCalendarService` (Anzahl verworfener Einträge, Anzahl
  verbleibender Warteschlangen-Einträge nach diesem Zyklus) wird ergänzt —
  eine reine Logging-Ergänzung ohne neue Konfigurations- oder
  Persistenzfläche, die für die betriebliche Beobachtung eines
  möglicherweise mehrtägigen Drainings sinnvoll, aber fachlich folgenlos
  ist.
- **`StateStore` bleibt vollständig unangetastet, `PendingCreationQueue` ist
  ein neuer, eigener Port statt einer Erweiterung.** Bereits oben unter
  "Port-Änderungen" begründet — hier nur als explizite Antwort auf die im
  Auftrag offen gelassene Frage festgehalten.
- **Reihenfolge des Drainings ist ausschließlich aufsteigend nach `start`**,
  über Zyklusgrenzen und Neustarts hinweg stabil, da sie direkt aus der
  Persistenz-Query (`findAllBySourceCalendarIdOrderByStartAsc`) kommt und nie
  im Anwendungscode neu berechnet oder mit einer anderen Sortierung
  vermischt wird (z. B. Einfüge-Reihenfolge beim Capture).

## Out of scope

- **Permanente, dauerhafte Ratenbegrenzung für Erstanlagen.** Diese Feature
  ist ausdrücklich ein **einmaliger** Ramp-up-Mechanismus für die
  Erstinitialisierung eines Kalenders. Ein künftiger, *wiederholter*
  Massen-Import in einen bereits initialisierten Kalender (z. B. Nutzer
  importiert nachträglich einen zweiten großen `.ics`-Export in seinen
  privaten Kalender) durchläuft **nicht** erneut die Warteschlange, sondern
  den gewöhnlichen, ungedrosselten Zyklus — mit demselben Flut-Risiko, das
  Issue #16 ursprünglich für die Erstinitialisierung beschrieben hat. Das ist
  eine bewusste, vom Projektverantwortlichen akzeptierte Vereinfachung
  (Vorgabe 8 der Konversation), keine Auslassung aus Versehen.
- **Pro-Kalender-Override von `burst-size`/`burst-interval`.** Beide sind
  postfachweite, globale Werte (siehe Konfiguration oben) — konsistent mit
  der bereits in `event-filtering.md` getroffenen Entscheidung, keine
  Pro-Kalender-Override-Fläche für Filterregeln zu bauen.
  `recurring-event-horizon` bleibt aus demselben Grund ebenfalls
  ausschließlich global.
- **Persistenter, neustartfester Zähler für `BurstBudget`.** Siehe "Weitere
  Entscheidungen" oben — bewusst als In-Memory-Lösung gebaut, mit
  dokumentiertem, akzeptiertem Restrisiko.
- **Erneute Live-Prüfung von `allDay`/`busy`/`cancelled`/tatsächlicher
  Existenz beim Draining.** Nur der Vergangenheits-Cutoff wird erneut
  geprüft (siehe oben) — exakt der Umfang, den die Konversation zu Issue #16
  vorgibt.
- **Inkrementelle Aufnahme neu hinzugekommener Termine in eine bereits
  laufende Warteschlange.** Neue Termine werden erst nach vollständigem
  Draining wieder sichtbar (siehe "Weitere Entscheidungen" oben).
- **Neue `RelayCycleResult`-Felder oder sonstige Buchführung über
  Warteschlangen-Fortschritt** außer den beschriebenen Log-Statements.
- **Änderungen an `CalendarSource`, `BlockerSink` oder `StateStore`.** Alle
  drei bleiben unangetastet.

## Open questions

- **Persistenter statt In-Memory-`BurstBudget`-Zähler.** Diese Spec
  entscheidet sich bewusst für die einfachere In-Memory-Variante mit einem
  eng umrissenen, dokumentierten Restart-Restrisiko (siehe "Weitere
  Entscheidungen" oben). Sollte sich in der Praxis zeigen, dass Neustarts
  während laufender Initialisierungen häufiger vorkommen als angenommen,
  ist diese Entscheidung erneut zu prüfen — der `BurstBudget`-Port-Vertrag
  selbst müsste dafür nicht geändert werden, nur die Adapter-Implementierung.
- **Betriebliche Sichtbarkeit des Drain-Fortschritts über Log-Zeilen
  hinaus.** Diese Spec begnügt sich bewusst mit `LOG.debug`/`LOG.info`
  (siehe "Weitere Entscheidungen" oben) und lehnt eine dedizierte
  Reporting-Fläche (z. B. ein Actuator-Endpunkt mit "N von M Terminen für
  Kalender X initialisiert") als nicht angefordert ab. Sollte der
  Projektverantwortliche für sehr große Bestände (deutlich über den in
  "Weitere Entscheidungen" angenommenen einigen hundert Terminen) doch
  Bedarf an aktiver Fortschrittsanzeige sehen, wäre das eine eigene,
  spätere Feature-Erweiterung, kein Teil dieser Spec.
