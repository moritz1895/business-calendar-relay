# ADR-009: In-Memory-Zähler für `BurstBudget`, kein persistenter, geteilter Zähler

**Datum:** 2026-07-30
**Status:** Angenommen

## Kontext

Das Draining des Initialisierungs-Rückstands (ADR-008) muss postfachweit,
über alle konfigurierten Quellkalender hinweg gemeinsam gedrosselt werden
— kein einzelner Kalender darf das Sendebudget für sich allein
beanspruchen, und keine Kalender-Instanz darf eine andere Kalender-Instanz
direkt kennen oder referenzieren (Vorgabe aus der Konversation zu
GitHub-Issue #16). `BurstBudget.tryAcquireSendSlot()` muss deshalb als
eine einzige, geteilte Instanz existieren, analog zum bereits etablierten
Muster von `BlockerSink` ("one configured instance, shared across all
source calendars").

Zur Wahl standen ein reiner In-Memory-Zähler (Fixed-Window, `synchronized`)
und ein persistenter, nebenläufigkeitssicherer Zähler über eine gemeinsame
Tabellenzeile mit atomarem `UPDATE ... WHERE`-Increment.

## Entscheidung

`InMemoryBurstBudgetAdapter` implementiert `BurstBudget` als reines
In-Memory-Fixed-Window-Zählwerk (`windowStart`, `sentInWindow`,
`synchronized`), konstruiert als einzige Instanz in
`RelayWiringConfiguration` mit dem bereits vorhandenen geteilten
`relayClock`-Bean. Der Zustand lebt ausschließlich im Prozessspeicher und
wird bei einem Neustart auf ein frisches Zeitfenster zurückgesetzt.

`synchronized` genügt: `tryAcquireSendSlot()` wird höchstens
`burstSize`-mal pro `burstInterval` über alle Kalender zusammen aufgerufen
(Default: 5-mal pro Stunde) — die Kontention ist strukturell
vernachlässigbar, ein lock-freier Mechanismus (z. B. `AtomicInteger` mit
CAS-Schleife) wäre unnötige Komplexität für dieses Zugriffsmuster.

Bewusst akzeptiertes Risiko: Ein Prozessneustart setzt das aktuelle
Zeitfenster zurück, statt den bereits in diesem Fenster verbrauchten Stand
mitzunehmen — bei einer sehr unglücklichen Abfolge wiederholter Neustarts
innerhalb desselben `burst-interval` könnte das Budget in Summe leicht
überschritten werden. Diese Feature ist ein weicher Anti-Spam-Schutz,
keine harte Zustellgarantie oder Compliance-Anforderung; ein persistenter,
nebenläufigkeitssicherer Zähler wäre spürbar mehr Implementierungsaufwand
für ein Risiko, das nur bei wiederholten Absturz-/Neustart-Zyklen innerhalb
eines einzigen Ein-Stunden-Fensters überhaupt zum Tragen kommt.

## Konsequenzen

- `tryAcquireSendSlot()` deklariert bewusst keine eigene Checked- oder
  Runtime-Exception im Vertrag — anders als `StateStore` und
  `PendingCreationQueue`, die echte Persistenz kapseln, ist dies eine
  reine, thread-sichere In-Memory-Entscheidung ohne I/O.
- Wiederholte Prozessneustarts innerhalb desselben `burst-interval` können
  das konfigurierte Sendebudget in Summe leicht überschreiten — ein eng
  umrissenes, dokumentiertes Restrisiko, keine harte Garantie.
- Sollte sich dieses Risiko in der Praxis als real erweisen, ist ein
  Wechsel auf einen persistenten, gemeinsam genutzten Zähler (z. B. eine
  einzelne Zeile in einer neuen Tabelle, atomar per `UPDATE ... WHERE`
  inkrementiert) der naheliegende spätere Ausbauschritt, ohne dass sich
  der `BurstBudget`-Port-Vertrag selbst (`boolean tryAcquireSendSlot()`)
  dafür ändern müsste — die Entscheidung ist auf Adapter-Ebene reversibel.
- Ein Prozessneustart während eines laufenden Drainings verliert keine
  Warteschlangen-Einträge (die sind in `pending_creation` persistiert) und
  erzeugt keine doppelten Versendungen (deterministische
  `blockerUid`-Ableitung plus `RelayState`-Existenzprüfung in
  Draining-Schritt 1) — das Restrisiko dieser ADR betrifft ausschließlich
  eine mögliche Überschreitung des Sendebudgets, nicht die Korrektheit
  oder Vollständigkeit des Drainings selbst.
