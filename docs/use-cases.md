# Use-Case-Katalog

Dieser Katalog beschreibt die Use Cases von business-calendar-relay auf
fachlicher Ebene, extrahiert aus `ports/inbound/`, `core/app/` und den
Entscheidungsregeln in `core/domain/`. Er beschreibt das **tatsächliche
Verhalten des Codes** auf dem Stand von `feat/scheduler-config-wiring` und
ist an einzelnen Stellen präziser bzw. abweichend von der ursprünglichen
Vorab-Spezifikation `docs/features/relay-orchestration.md` — solche Stellen
sind explizit als "Abweichung von der Spezifikation" markiert.

## Bereich: Kalender-Relay

### Use Case: Poll and Relay Source Calendar

Treibender Port: `PollAndRelaySourceCalendarUseCase`
(`ports/inbound/PollAndRelaySourceCalendarUseCase.java`).
Implementierung: `PollAndRelaySourceCalendarService`
(`core/app/PollAndRelaySourceCalendarService.java`).

#### Akteur

**Scheduler.** Es gibt keinen interaktiven menschlichen Akteur. Pro
konfiguriertem Quellkalender existiert genau eine Instanz dieses Use Case,
konfiguriert mit dem zugehörigen `CalendarSource`, `StateStore`, den
iMIP-Identitätsadressen (Organisator, Teilnehmer, Absender, Reply-To) und
einer gemeinsam genutzten Uhr. Der einzige Aufrufer im laufenden System ist
`PollAndRelaySchedulerAdapter`, der nach vollständigem Anwendungsstart für
jede Use-Case-Instanz einen wiederkehrenden Zyklus mit fester Verzögerung
anstößt. Der menschliche Effekt dieses Use Case ist ausschließlich
indirekt sichtbar: Blocker erscheinen, verschieben sich oder werden im
dienstlichen Outlook-Kalender abgesagt.

#### Ziel

Die Blocker im dienstlichen Kalender für einen Quellkalender wieder mit
dessen aktuellen Terminen in Übereinstimmung bringen — dabei werden nur
die iMIP-Nachrichten verschickt, die nötig sind, um die seit dem letzten
Poll eingetretenen Änderungen abzubilden. Bei der allerersten Erstanlage-
Welle eines neu angebundenen Kalenders wird der Versand zusätzlich zeitlich
entzerrt, um den Mailserver des Business-Postfachs nicht als Spam-Quelle
verdächtig zu machen (siehe "Initialisierungs-Rückstand" im Hauptablauf
unten).

#### Vorbedingungen

- Für diese Use-Case-Instanz ist genau ein Quellkalender konfiguriert
  (eine `CalendarSource`- und eine `StateStore`-Instanz, beide auf diesen
  Kalender gescoped).
- Organisator- und Teilnehmeradresse sowie Absender-/Reply-To-Adresse für
  jeden aus diesem Kalender erzeugten Blocker sind konfiguriert.
- Eine Uhr (`Clock`) ist verfügbar, aus der der `DTSTAMP`-Zeitstempel für
  jede gerenderte iMIP-Nachricht abgeleitet wird, sowie das `now`, gegen das
  der Erstellungs-Filter jeden Zyklus frisch ausgewertet wird.
- Ein Wiederholungs-Zeitfenster (`recurringEventHorizon`) ist konfiguriert
  — global für alle Quellkalender, siehe README.
- Für diese Use-Case-Instanz ist eine `PendingCreationQueue`-Instanz
  konfiguriert, gescoped auf denselben Quellkalender wie ihre `StateStore`-
  Instanz — sie führt diesen Kalenders Initialisierungs-Rückstand (siehe
  `docs/domain.md`).
- Eine einzige, über alle Use-Case-Instanzen geteilte `BurstBudget`-Instanz
  ist konfiguriert — kein Kalender kennt eine andere Kalender-Instanz,
  alle teilen sich denselben Sendebudget-Zähler.
- Ein Sendebudget (`burst-size`, `burst-interval`) ist konfiguriert —
  global für alle Quellkalender, siehe README.
- Für diesen Quellkalender ist optional Delta-Sync aktiviert
  (`delta-sync-enabled`, Default `true`, pro Kalender konfigurierbar) — reine
  Konfiguration **der Beschaffungsart** von `CalendarSource`, ohne jeden
  Einfluss auf den restlichen Ablauf dieses Use Case (siehe eigener
  Abschnitt "CalDAV-Beschaffung" weiter unten).

#### Kommandofelder

Ein Poll-Zyklus nimmt **kein ereignisspezifisches Eingabefeld** entgegen —
er ist ein Auslöser, keine Anfrage zu einem bestimmten Termin. Der
Quellkalender, die Identitätsadressen, die Uhr und das
Wiederholungs-Zeitfenster sind Use-Case-Konfiguration (Konstruktorargumente),
keine Aufrufparameter.

#### Hauptablauf

Der Use Case läuft pro Zyklus in genau einem von zwei sich gegenseitig
ausschließenden Modi, entschieden allein danach, ob dieser Kalenders
Initialisierungs-Rückstand (siehe `docs/domain.md`) gerade leer ist:
**Capture-und-Drain** für die Initialisierungsphase eines Kalenders, oder
**gewöhnlicher Zyklus** danach. `RelayDiffPlanner.plan(...)` wird dabei
höchstens einmal pro Zyklus aufgerufen — nie zweimal, egal welcher Modus
greift.

1. Der Use Case liest jeden bekannten Relay-Zustand (`priorStates`) für
   diesen Quellkalender über `StateStore.loadAll()` — aktive wie
   abgesagte Einträge gleichermaßen.
2. Der Use Case liest den Initialisierungs-Rückstand dieses Kalenders
   (`pendingQueue`) über `PendingCreationQueue.loadAllOrderedByStart()` —
   aufsteigend nach `start` sortiert.
3. **Erst-Capture — nur wenn `pendingQueue` und `priorStates` beide leer
   sind** (das ist die einzige Bedingung, unter der "noch nie
   initialisiert" gilt, siehe `docs/domain.md`):
   - Der Use Case liest die vollständige, aktuelle Menge an Terminen
     (`currentEvents`) über `CalendarSource.readEvents()`.
   - `RelayDiffPlanner` berechnet mit `plan(currentEvents, priorStates =
     [], now, recurringEventHorizon)` die vollständige Erstanlage-Liste.
     Da `priorStates` hier per Definition leer ist, kann `plan(...)`
     strukturell ausschließlich Erstellungen liefern, nie Aktualisierungen
     oder Absagen.
   - Die berechneten Erstellungen werden aufsteigend nach `start` sortiert
     und vollständig, in einem Rutsch, über
     `PendingCreationQueue.saveAll(...)` persistiert, **bevor** irgendetwas
     versendet wird — ein Absturz vor dem ersten Versand verliert dadurch
     nichts.
   - `pendingQueue` ist ab jetzt für den Rest dieses Zyklus diese frisch
     gespeicherte, sortierte Liste.
4. **Ist `pendingQueue` (nach optionalem Schritt 3) nicht leer**, läuft
   ausschließlich das Draining des Initialisierungs-Rückstands (siehe
   eigener Abschnitt unten) — der gewöhnliche Poll-and-Diff-Zyklus
   (Schritt 5) wird für diesen Kalender in diesem Zyklus **nicht**
   ausgeführt. Der Zyklus endet danach mit dem `RelayCycleResult` des
   Drainings.
5. **Ist `pendingQueue` leer** (entweder weil in Schritt 3 nichts
   erstellungsberechtigt war, oder weil ein zuvor nicht leerer Rückstand
   inzwischen vollständig abgearbeitet wurde), läuft exakt der gewöhnliche,
   unveränderte Zyklus:
   - Der Use Case liest die vollständige, aktuelle Menge an Terminen
     (`currentEvents`) über `CalendarSource.readEvents()`. Das ist immer
     eine vollständige Momentaufnahme, nie ein Delta — unabhängig davon, ob
     `CalendarSource` diese Momentaufnahme intern per Delta-Sync oder per
     vollständiger Anfrage beschafft hat (siehe "CalDAV-Beschaffung:
     Delta-Sync" weiter unten).
   - `RelayDiffPlanner` berechnet aus `currentEvents`, `priorStates`, dem
     aktuellen Zeitpunkt (`now`) und dem konfigurierten
     `recurringEventHorizon` eine Liste von Aktionen — je Quelltermin
     höchstens eine Entscheidung:
     - **Erstellung** für einen Quelltermin ohne vorherigen Zustand, der
       zusätzlich den Erstellungs-Filter besteht (`isEligibleForCreation`
       — Start nicht in der Vergangenheit, nicht ganztägig, als
       "beschäftigt" markiert, nicht storniert markiert, Start fällt nicht
       auf Samstag oder Sonntag, und, nur bei wiederkehrenden Terminen,
       Start innerhalb des konfigurierten Wiederholungs-Zeitfensters).
       Besteht ein Quelltermin ohne
       vorherigen Zustand diesen Filter nicht, wird er für diesen Zyklus
       einfach übersprungen — kein Rendern, kein Versand, kein
       `RelayState`-Eintrag. Er wird beim nächsten Poll erneut
       unvoreingenommen gegen den Filter geprüft, ohne dass dafür eine
       eigene Logik nötig ist: Der Filter wird ohnehin bei jedem Zyklus
       frisch gegen das dann aktuelle `now` ausgewertet, sodass ein
       inzwischen ins Zeitfenster gerückter oder anders markierter Termin
       automatisch berücksichtigt wird.
     - **Aktualisierung** für einen aktiven Quelltermin, dessen
       Zeitfenster oder dessen ganztägig-/beschäftigt-/storniert-
       Markierung sich geändert hat, oder für einen zuvor abgesagten
       Quelltermin, der wieder vorhanden ist ("Wiederauferstehung" — kein
       Sonderfall, sondern natürliche Folge der Behandlung eines
       abgesagten Eintrags als aktualisierbar).
     - **Keine Aktion** für einen aktiven Quelltermin ohne Abweichung in
       einem dieser Felder.
     - **Absage** für einen vorher aktiven Quelltermin, der im aktuellen
       Poll nicht mehr vorhanden ist.

     Der Erstellungs-Filter wird ausschließlich für den ersten Punkt oben
     befragt — für jeden Quelltermin mit bereits vorhandenem `RelayState`
     (aktiv oder bereits abgesagt) hat er keinerlei Einfluss; ein Blocker
     wird auch weiterhin ausschließlich dann abgesagt, wenn sein
     Quelltermin tatsächlich aus `CalendarSource.readEvents()`
     verschwindet, nie weil er nachträglich den Filter nicht mehr bestehen
     würde. Die vollständigen Entscheidungsregeln stehen in
     `docs/domain.md`.
   - Für jede Erstellungs- oder Aktualisierungs-Aktion: Der Use Case baut
     ein `BlockerEvent` aus dem Zeitfenster der Aktion und den
     konfigurierten Identitätsadressen, rendert es mit
     `ImipCalendarRenderer.renderRequest` zu iMIP-Text (`METHOD:REQUEST`),
     verpackt ihn in eine `BlockerMail` und versendet sie über
     `BlockerSink.send`. **Nur bei erfolgreichem Versand** wird der neue
     `RelayState` (mit `active = true`) über `StateStore.save` gespeichert
     und der Quelltermin als erfolgreich erstellt bzw. aktualisiert
     gezählt.
   - Für jede Absage-Aktion: Der Use Case baut das entsprechende
     `BlockerEvent`, rendert es mit `ImipCalendarRenderer.renderCancel` zu
     iMIP-Text (`METHOD:CANCEL`) und versendet ihn über `BlockerSink.send`.
     **Nur bei erfolgreichem Versand** wird `StateStore.markCancelled` mit
     der neuen `SEQUENCE` aufgerufen (der Eintrag bleibt bestehen, wird
     aber als `active = false` markiert) und der Quelltermin als
     erfolgreich abgesagt gezählt.
   - Ab hier gibt es keinerlei Unterschied mehr zum Verhalten vor
     Einführung des Initialisierungs-Rückstands — dieser Kalender ist ab
     jetzt dauerhaft im "initialisierten" Zustand, ohne dass das irgendwo
     explizit vermerkt wird (siehe `docs/domain.md`).
6. Der Zyklus liefert ein `RelayCycleResult` mit den `sourceUid`s aller
   erfolgreichen Erstellungen, Aktualisierungen und Absagen sowie einer
   Liste aller fehlgeschlagenen Versandversuche zurück.

#### Draining des Initialisierungs-Rückstands

Nur relevant, solange Schritt 4 oben greift. Für jeden Eintrag `item` aus
`pendingQueue`, in aufsteigender `start`-Reihenfolge:

1. **Bereits anderweitig verarbeitet?** Existiert bereits ein `RelayState`
   für `item.sourceUid()` in `priorStates`, wurde dieser Eintrag in einem
   früheren, durch einen Neustart unterbrochenen Draining-Durchlauf bereits
   erfolgreich versendet und gespeichert, nur seine Warteschlangen-Zeile
   wurde noch nicht entfernt. → Zeile aus `PendingCreationQueue` entfernen,
   kein erneuter Versand, weiter mit dem nächsten Eintrag.
2. **Veraltet?** Ist `item.start()` laut
   `RelayDiffPlanner.isPastCreationCutoff(item.start(), now)` inzwischen in
   die Vergangenheit gerückt, seit der Eintrag beim Capture erfasst wurde
   → Zeile aus `PendingCreationQueue` entfernen, **kein Versand, kein
   `RelayState`**. Weiter mit dem nächsten Eintrag.
3. **Budget verfügbar?** `BurstBudget.tryAcquireSendSlot()`. Liefert dieser
   Aufruf `false` (postfachweites Budget für das aktuelle Zeitfenster
   ausgeschöpft — von diesem oder einem anderen Kalender), wird das
   Draining für diesen gesamten Zyklus sofort abgebrochen; alle noch nicht
   betrachteten Einträge bleiben unverändert in der Warteschlange und
   werden im nächsten Zyklus erneut versucht, ganz oben beginnend.
4. **Senden.** Liefert `tryAcquireSendSlot()` `true`, wird `item` — bereits
   ein vollwertiges `RelayAction.Create` — genau wie eine gewöhnliche
   Erstellung (Schritt 5, "gewöhnlicher Zyklus" oben) verarbeitet: gleiches
   Rendering, gleicher `BlockerSink.send`-Aufruf, gleiches `StateStore.save`
   bei Erfolg, gleiche Fehlerisolation bei Misserfolg — kein zweiter,
   paralleler Sende-Pfad.
   - Bei Erfolg: Zeile aus `PendingCreationQueue` entfernen.
   - Bei Misserfolg (Eintrag landet in `failed`, `StateStore` unverändert):
     Zeile bleibt in `PendingCreationQueue` stehen und wird im nächsten
     Zyklus erneut versucht — dieselbe Retry-Semantik wie für gewöhnliche
     Erstanlagen. Der zuvor verbrauchte Budget-Slot wird **nicht**
     zurückgegeben.
   - Danach weiter mit dem nächsten Eintrag (erneut ab Schritt 3, solange
     noch Einträge übrig sind).

Ein einzelner Draining-Zyklus kann so, wenn das Budget es hergibt, mehrere
Einträge auf einmal versenden — die eigentliche Drosselung liegt
vollständig im `BurstBudget`, nicht in einer zusätzlichen
Pro-Zyklus-Obergrenze dieser Methode.

#### CalDAV-Beschaffung: Delta-Sync (adapterinterne Optimierung, für diesen Use Case transparent)

Betrifft ausschließlich, **wie** `CalendarSource.readEvents()` in Schritt 3
und 5 oben intern zu seiner vollständigen `currentEvents`-Momentaufnahme
kommt — der Use Case selbst, `RelayDiffPlanner` und `StateStore` sehen davon
nichts und verhalten sich in jedem der folgenden Fälle identisch. Nur
relevant, solange `delta-sync-enabled` für diesen Kalender nicht auf `false`
gesetzt ist (siehe Vorbedingungen oben); ist Delta-Sync deaktiviert, liest
`readEvents()` unverändert wie vor diesem Feature stets vollständig.

- **Noch nie synchronisiert (kein Sync-Token bekannt).** `readEvents()`
  fragt beim CalDAV-Server einmalig vollständig ab und erhält dabei zugleich
  einen neuen Sync-Token, den es zusammen mit den gelesenen Ressourcen
  speichert. Kostet dieselbe Serverlast wie die bisherige, stets
  vollständige Anfrage — kein Regressionsrisiko gegenüber vor diesem
  Feature.
- **Bereits ein Sync-Token bekannt.** `readEvents()` fragt den Server
  ausschließlich nach den seit dem letzten Poll geänderten oder entfernten
  Ressourcen und erhält dabei zugleich einen neuen Sync-Token. Nur diese
  Änderungen werden übertragen; die vollständige `currentEvents`-Menge wird
  anschließend trotzdem bei jedem Aufruf frisch aus **allen** lokal bekannten
  Ressourcen (den unveränderten plus den soeben aktualisierten) berechnet —
  eine wiederkehrende Serie, deren zugrunde liegende Ressource sich nie
  ändert, liefert dadurch weiterhin bei jedem Zyklus neue Vorkommen, sobald
  das Wiederholungs-Zeitfenster weiter fortschreitet.
- **Der Server erklärt den bekannten Sync-Token für ungültig.** Der Adapter
  erkennt dies an den beiden von RFC 6578 vorgesehenen Signalen und führt
  automatisch, ohne manuellen Eingriff, einmalig eine vollständige
  Neusynchronisation durch (wie beim allerersten Poll oben) — kein
  Datenverlust, im ungünstigsten Fall dieselbe Serverlast wie eine
  gewöhnliche vollständige Anfrage.
- **Der Server unterstützt Delta-Sync für diese Collection erkennbar nicht.**
  Nur ein eng gefasstes, konkretes Set an Serverantworten wird als
  eindeutiges "nicht unterstützt"-Signal gewertet (siehe ADR-011 für die
  Begründung dieser bewussten Enge). Trifft eines davon ein, schaltet der
  Adapter für die verbleibende Lebensdauer dieser Prozessinstanz dauerhaft
  auf die bisherige, stets vollständige Anfrage zurück — ohne Datenverlust,
  einmalig protokolliert. Ein Neustart des Prozesses versucht Delta-Sync
  erneut.
- **Der Server antwortet unerwartet, aber nicht eindeutig als
  "nicht unterstützt" erkennbar** (z. B. ein vorübergehender
  Serverfehler). Dies wird **nicht** als fehlende Unterstützung gewertet —
  der Adapter schaltet nicht dauerhaft um. Stattdessen schlägt lediglich
  dieser eine Poll-Zyklus fehl, exakt wie jeder andere fehlgeschlagene
  `readEvents()`-Aufruf (siehe Fehlerfälle unten); der nächste Zyklus
  versucht Delta-Sync erneut, mit demselben, weiterhin gültigen Sync-Token.
  Diese bewusste Unterscheidung verhindert, dass ein einzelner, vorüber­
  gehender Serverfehler einen gesunden Kalender fälschlich und dauerhaft auf
  den langsameren Beschaffungsweg abschieben würde — siehe ADR-011.

Unabhängig davon, welcher der obigen Fälle in einem gegebenen Zyklus
eintritt: Das Ergebnis ist immer dieselbe Art von vollständiger
`currentEvents`-Momentaufnahme, berechnet nach denselben Regeln
(`event-filtering.md`) wie zuvor. `RelayDiffPlanner` trifft seine
Entscheidungen (Erstellung, Aktualisierung, Absage, keine Aktion) exakt wie
vor diesem Feature.

#### Fehlerfälle

- **`CalendarSource.readEvents()` schlägt fehl.** Es wurde in diesem Zyklus
  noch nichts versendet oder gespeichert; der Aufruf bricht komplett ab und
  die Ausnahme wird unverändert an den Aufrufer weitergereicht. Im
  laufenden System fängt `PollAndRelaySchedulerAdapter` diese Ausnahme
  generisch ab, protokolliert sie als "Poll cycle aborted unexpectedly" und
  lässt den nächsten planmäßigen Zyklus unberührt.
- **Bei aktiviertem Delta-Sync: Laden oder Schreiben des Sync-Tokens bzw.
  der lokalen Ressourcen-Replik schlägt fehl.** Wird unverändert als
  fehlgeschlagener `CalendarSource.readEvents()`-Aufruf behandelt (siehe
  oben) — derselbe vollständige Zyklusabbruch, dasselbe generische
  "Poll cycle aborted unexpectedly"-Logging. Ein Absturz zwischen einer
  bereits erfolgreich empfangenen Delta-Antwort des Servers und deren
  Speicherung ist dabei folgenlos: Der nächste Zyklus fragt beim Server
  erneut mit dem alten, zuletzt erfolgreich gespeicherten Sync-Token an (der
  aus der verlorenen Antwort stammende neue Token wurde ja nie persistiert)
  und erhält denselben Delta ein zweites Mal — kein Datenverlust, keine
  Lücke in `currentEvents`.
- **`StateStore.loadAll()` schlägt fehl.** Gleiches Verhalten wie oben —
  vollständiger Abbruch, da noch nichts versendet wurde.
- **`BlockerSink.send(...)` schlägt für eine einzelne Erstellung,
  Aktualisierung oder Absage fehl.** Dies ist der einzige Fehlerfall, den
  der Use Case als **Isolation statt Abbruch** behandelt: Der Fehler wird
  protokolliert und der betroffene Quelltermin landet in der
  `failed`-Liste des Ergebnisses; `StateStore` wird für diesen Quelltermin
  **nicht** verändert, sodass der nächste Poll dieselbe Entscheidung
  (gleiche `blockerUid`, gleiche `SEQUENCE`) erneut trifft und den Versand
  wiederholt. Alle übrigen Aktionen desselben Zyklus werden unabhängig
  davon normal weiterverarbeitet.
- **`StateStore.save(...)` bzw. `StateStore.markCancelled(...)` schlägt
  nach einem erfolgreichen Versand fehl.** **Abweichung von der
  Spezifikation:** `docs/features/relay-orchestration.md` beschreibt diesen
  Fall nur als "bewusst nicht besonders behandelt" mit der Konsequenz einer
  veralteten Baseline beim nächsten Poll, ohne die Auswirkung auf den
  laufenden Zyklus selbst zu benennen. Im tatsächlichen Code ist dieser
  Aufruf **nicht** durch eine eigene Fehlerbehandlung abgesichert (anders
  als `BlockerSink.send`, das per `trySend` isoliert wird): Eine dabei
  geworfene Laufzeitausnahme verlässt `pollAndRelay()` unmittelbar und
  bricht damit die Verarbeitung **aller noch ausstehenden Aktionen
  desselben Zyklus** ab — nicht nur die des betroffenen Quelltermins. Für
  bereits erfolgreich abgeschlossene Aktionen des Zyklus bleibt das
  Ergebnis erhalten (sie wurden bereits gesendet und gespeichert), aber
  noch nicht verarbeitete Aktionen in der Liste werden in diesem Zyklus gar
  nicht mehr versucht und erst im nächsten planmäßigen Poll erneut
  berechnet. Aus Sicht des Scheduler-Adapters ist dies nicht von einem
  fehlgeschlagenen `readEvents()`/`loadAll()` zu unterscheiden — beide
  landen im generischen "Poll cycle aborted unexpectedly"-Log.
- **`PendingCreationQueue.loadAllOrderedByStart()` schlägt fehl.** Gleiches
  Verhalten wie ein fehlgeschlagenes `StateStore.loadAll()`: Es wurde in
  diesem Zyklus noch nichts versendet, der Aufruf bricht komplett ab.
- **`PendingCreationQueue.saveAll(...)` beim Erst-Capture schlägt fehl.**
  Gleiches Verhalten: Es wurde noch keine einzige Erstanlage aus dieser
  Berechnung versendet, voller Abbruch ist sicher. Der nächste Poll-Zyklus
  sieht wieder `pendingQueue` und `priorStates` beide leer vor und
  wiederholt das Capture unverändert.
- **`PendingCreationQueue.remove(sourceUid)` schlägt nach erfolgreichem
  Versand/Speichern fehl.** **Abweichung vom sonstigen Fehlerverhalten
  dieses Use Case:** Wird bewusst **nicht** wie ein fehlgeschlagenes
  `StateStore.save`/`markCancelled` behandelt (das den gesamten Zyklus
  abbricht) — der `RelayState`-Existenzcheck in Draining-Schritt 1 macht
  eine liegengebliebene Zeile beim nächsten Zyklus ohnehin folgenlos. Der
  Fehler wird protokolliert, ohne die Verarbeitung der übrigen
  Warteschlangen-Einträge desselben Zyklus zu unterbrechen.
- **`BurstBudget.tryAcquireSendSlot()` wirft eine Ausnahme.** Wird nicht
  erwartet (reine In-Memory-Entscheidung ohne I/O) und deshalb bewusst
  nicht isoliert: Eine hier geworfene Laufzeitausnahme verhält sich wie
  jede andere unerwartete Ausnahme in diesem Use Case und bricht den
  Zyklus vollständig ab.

#### Ergebnis (`RelayCycleResult`)

| Feld | Bedeutung |
|---|---|
| `created` | `sourceUid`s aller in diesem Zyklus erfolgreich neu erstellten Blocker — im Draining-Modus die aus dem Initialisierungs-Rückstand erfolgreich versendeten Erstanlagen. |
| `updated` | `sourceUid`s aller in diesem Zyklus erfolgreich aktualisierten Blocker. |
| `cancelled` | `sourceUid`s aller in diesem Zyklus erfolgreich abgesagten Blocker. |
| `failed` | Liste aus `sourceUid` und Fehlerursache für jeden Quelltermin, dessen Versand fehlgeschlagen ist. Für diese Einträge wurde `StateStore` nicht verändert; sie werden beim nächsten Poll erneut mit derselben Entscheidung versucht. |

Während eines Draining-Zyklus sind `updated` und `cancelled` strukturell
immer leer — Draining erzeugt ausschließlich Erstanlagen oder Fehlschläge,
nie Aktualisierungen oder Absagen. Ein beim Draining als veraltet
verworfener Rückstands-Eintrag (siehe "Draining des Initialisierungs-
Rückstands" oben) taucht in keiner der vier Listen auf, insbesondere nicht
in `failed` — es handelt sich um keinen Fehler, sondern denselben Fall wie
ein Quelltermin, der den gewöhnlichen Erstellungs-Filter nicht besteht.
`PollAndRelaySourceCalendarService` protokolliert Anzahl verworfener und
Anzahl verbleibender Rückstands-Einträge zusätzlich auf DEBUG-/INFO-Ebene —
eine reine Logging-Ergänzung ohne eigenes `RelayCycleResult`-Feld.

Der Aufrufer (der Scheduler-Adapter) protokolliert dieses Ergebnis: auf
INFO-Ebene bei leerem `failed`, sonst auf WARN-Ebene mit den betroffenen
`sourceUid`s. Es gibt aktuell keinen weiteren Konsumenten dieses Ergebnisses
(kein Alerting, keine Wiederholungszählung über einen einzelnen Zyklus
hinaus).

#### Randbedingungen ohne eigene Fallunterscheidung

- Ein aktiver Quelltermin ohne Abweichung in Zeitfenster, ganztägig-,
  beschäftigt- oder storniert-Markierung erzeugt **keine** Aktion und
  taucht damit auch in keiner der vier `RelayCycleResult`-Listen auf — ein
  "stiller" Zyklus für diesen Quelltermin ist der Normalfall, nicht ein
  Fehler oder eine besondere Meldung.
- Die "Wiederauferstehung" eines zuvor abgesagten Quelltermins ist keine
  eigene Fehlerbehandlung oder Sonderlogik im Use Case — sie ergibt sich
  ausschließlich aus der Domänenregel, abgesagte `RelayState`-Einträge
  aufzubewahren (siehe `docs/domain.md`), und wird vom Use Case identisch
  zu jeder anderen Aktualisierung behandelt.
- Ein Quelltermin ohne vorherigen `RelayState`, der den Erstellungs-Filter
  nicht besteht, erzeugt ebenfalls **keine** Aktion und taucht damit in
  keiner der vier `RelayCycleResult`-Listen auf — insbesondere nicht in
  `failed`, da es sich um keinen Fehler handelt. Er wird beim nächsten
  Poll erneut unvoreingenommen gegen den Filter geprüft; es gibt keine
  eigene Buchführung darüber, welche Quelltermine in einem Zyklus
  übersprungen wurden.
- Ein Kalender ohne jemals ein einziges erstellungsberechtigtes Ereignis
  (Rückstand bleibt für immer leer, `StateStore` bleibt für immer leer)
  wiederholt bei jedem Zyklus lediglich harmlos das Erst-Capture (Schritt
  3) — kein Unterschied im Verhalten gegenüber dem gewöhnlichen Zyklus,
  keine Endlosschleife, kein zusätzlicher Zustand, der inkonsistent werden
  könnte.
- Neue Termine, die während der Drain-Phase eines Kalenders am
  Quellkalender hinzukommen, werden erst sichtbar, sobald der
  Initialisierungs-Rückstand vollständig abgearbeitet ist und der
  gewöhnliche Zyklus (Schritt 5) wieder frische Quelltermine liest — eine
  inkrementelle Aufnahme neu hinzugekommener Termine in einen bereits
  laufenden Rückstand findet nicht statt.
- Ein bereits initialisierter Kalender, in dem nachträglich viele neue
  Termine auf einmal auftauchen (z. B. ein Massen-Import in den privaten
  Kalender), durchläuft **nicht** erneut den Initialisierungs-Rückstand,
  sondern den gewöhnlichen, ungedrosselten Zyklus — der Initialisierungs-
  Rückstand ist ausdrücklich ein einmaliger Ramp-up-Mechanismus für die
  Erstinitialisierung eines Kalenders, keine dauerhafte Ratenbegrenzung.
