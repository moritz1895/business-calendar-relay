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
Poll eingetretenen Änderungen abzubilden.

#### Vorbedingungen

- Für diese Use-Case-Instanz ist genau ein Quellkalender konfiguriert
  (eine `CalendarSource`- und eine `StateStore`-Instanz, beide auf diesen
  Kalender gescoped).
- Organisator- und Teilnehmeradresse sowie Absender-/Reply-To-Adresse für
  jeden aus diesem Kalender erzeugten Blocker sind konfiguriert.
- Eine Uhr (`Clock`) ist verfügbar, aus der der `DTSTAMP`-Zeitstempel für
  jede gerenderte iMIP-Nachricht abgeleitet wird.

#### Kommandofelder

Ein Poll-Zyklus nimmt **kein ereignisspezifisches Eingabefeld** entgegen —
er ist ein Auslöser, keine Anfrage zu einem bestimmten Termin. Der
Quellkalender, die Identitätsadressen und die Uhr sind
Use-Case-Konfiguration (Konstruktorargumente), keine Aufrufparameter.

#### Hauptablauf

1. Der Use Case liest die vollständige, aktuelle Menge an Terminen
   (`currentEvents`) über `CalendarSource.readEvents()`. Das ist immer eine
   vollständige Momentaufnahme, nie ein Delta.
2. Der Use Case liest jeden bekannten Relay-Zustand (`priorStates`) für
   diesen Quellkalender über `StateStore.loadAll()` — aktive wie
   abgesagte Einträge gleichermaßen. Das ist die einzige Lesung von
   `StateStore` in diesem Zyklus; sie bildet die Vergleichsbasis für die
   gesamte Diff-Entscheidung.
3. `RelayDiffPlanner` berechnet aus `currentEvents` und `priorStates` eine
   Liste von Aktionen — je Quelltermin höchstens eine Entscheidung:
   - **Erstellung** für einen Quelltermin ohne vorherigen Zustand.
   - **Aktualisierung** für einen aktiven Quelltermin mit geändertem
     Zeitfenster, oder für einen zuvor abgesagten Quelltermin, der wieder
     vorhanden ist ("Wiederauferstehung" — kein Sonderfall, sondern
     natürliche Folge der Behandlung eines abgesagten Eintrags als
     aktualisierbar).
   - **Keine Aktion** für einen aktiven Quelltermin mit unverändertem
     Zeitfenster.
   - **Absage** für einen vorher aktiven Quelltermin, der im aktuellen
     Poll nicht mehr vorhanden ist.

   Die vollständigen Entscheidungsregeln stehen in `docs/domain.md`.
4. Für jede Erstellungs- oder Aktualisierungs-Aktion: Der Use Case baut ein
   `BlockerEvent` aus dem Zeitfenster der Aktion und den konfigurierten
   Identitätsadressen, rendert es mit `ImipCalendarRenderer.renderRequest`
   zu iMIP-Text (`METHOD:REQUEST`), verpackt ihn in eine `BlockerMail` und
   versendet sie über `BlockerSink.send`. **Nur bei erfolgreichem Versand**
   wird der neue `RelayState` (mit `active = true`) über `StateStore.save`
   gespeichert und der Quelltermin als erfolgreich erstellt bzw.
   aktualisiert gezählt.
5. Für jede Absage-Aktion: Der Use Case baut das entsprechende
   `BlockerEvent`, rendert es mit `ImipCalendarRenderer.renderCancel` zu
   iMIP-Text (`METHOD:CANCEL`) und versendet ihn über `BlockerSink.send`.
   **Nur bei erfolgreichem Versand** wird `StateStore.markCancelled` mit
   der neuen `SEQUENCE` aufgerufen (der Eintrag bleibt bestehen, wird aber
   als `active = false` markiert) und der Quelltermin als erfolgreich
   abgesagt gezählt.
6. Der Zyklus liefert ein `RelayCycleResult` mit den `sourceUid`s aller
   erfolgreichen Erstellungen, Aktualisierungen und Absagen sowie einer
   Liste aller fehlgeschlagenen Versandversuche zurück.

#### Fehlerfälle

- **`CalendarSource.readEvents()` schlägt fehl.** Es wurde in diesem Zyklus
  noch nichts versendet oder gespeichert; der Aufruf bricht komplett ab und
  die Ausnahme wird unverändert an den Aufrufer weitergereicht. Im
  laufenden System fängt `PollAndRelaySchedulerAdapter` diese Ausnahme
  generisch ab, protokolliert sie als "Poll cycle aborted unexpectedly" und
  lässt den nächsten planmäßigen Zyklus unberührt.
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

#### Ergebnis (`RelayCycleResult`)

| Feld | Bedeutung |
|---|---|
| `created` | `sourceUid`s aller in diesem Zyklus erfolgreich neu erstellten Blocker. |
| `updated` | `sourceUid`s aller in diesem Zyklus erfolgreich aktualisierten Blocker. |
| `cancelled` | `sourceUid`s aller in diesem Zyklus erfolgreich abgesagten Blocker. |
| `failed` | Liste aus `sourceUid` und Fehlerursache für jeden Quelltermin, dessen Versand fehlgeschlagen ist. Für diese Einträge wurde `StateStore` nicht verändert; sie werden beim nächsten Poll erneut mit derselben Entscheidung versucht. |

Der Aufrufer (der Scheduler-Adapter) protokolliert dieses Ergebnis: auf
INFO-Ebene bei leerem `failed`, sonst auf WARN-Ebene mit den betroffenen
`sourceUid`s. Es gibt aktuell keinen weiteren Konsumenten dieses Ergebnisses
(kein Alerting, keine Wiederholungszählung über einen einzelnen Zyklus
hinaus).

#### Randbedingungen ohne eigene Fallunterscheidung

- Ein aktiver Quelltermin mit unverändertem Zeitfenster erzeugt **keine**
  Aktion und taucht damit auch in keiner der vier `RelayCycleResult`-Listen
  auf — ein "stiller" Zyklus für diesen Quelltermin ist der Normalfall,
  nicht ein Fehler oder eine besondere Meldung.
- Die "Wiederauferstehung" eines zuvor abgesagten Quelltermins ist keine
  eigene Fehlerbehandlung oder Sonderlogik im Use Case — sie ergibt sich
  ausschließlich aus der Domänenregel, abgesagte `RelayState`-Einträge
  aufzubewahren (siehe `docs/domain.md`), und wird vom Use Case identisch
  zu jeder anderen Aktualisierung behandelt.
