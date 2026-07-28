# ADR-005: Continue-and-report statt Abort-on-first-failure bei fehlgeschlagenem Versand

**Datum:** 2026-07-28
**Status:** Angenommen

## Kontext

Innerhalb eines Poll-Zyklus kann `BlockerSink.send(...)` für einzelne
Quelltermine fehlschlagen (z. B. vorübergehende SMTP-Störung). Ein Zyklus
verarbeitet aber typischerweise mehrere Quelltermine mit unabhängigen
Entscheidungen (Erstellung, Aktualisierung, Absage). Die Frage ist, ob ein
einzelner fehlgeschlagener Versand den gesamten restlichen Zyklus abbrechen
soll oder nicht.

## Entscheidung

`PollAndRelaySourceCalendarService` isoliert einen fehlgeschlagenen
`BlockerSink.send(...)`-Aufruf auf genau den betroffenen Quelltermin
(`trySend(...)` fängt `BlockerSinkException`, protokolliert sie und trägt
den Quelltermin in die `failed`-Liste des Ergebnisses ein). Alle übrigen
Aktionen desselben Zyklus werden unabhängig davon normal weiterverarbeitet.
`StateStore` wird für den fehlgeschlagenen Quelltermin nicht verändert,
sodass der nächste planmäßige Poll-Zyklus dieselbe Entscheidung erneut
trifft und den Versand von selbst wiederholt — es gibt keine eigene
Retry- oder Backoff-Logik innerhalb eines Zyklus.

Diese Isolation gilt ausdrücklich **nur** für `BlockerSink.send(...)`.
Fehlschläge beim initialen Lesen (`CalendarSource.readEvents()`,
`StateStore.loadAll()`) brechen weiterhin den gesamten Zyklus ab, da zu
diesem Zeitpunkt noch nichts versendet wurde und es keinen Teilzustand zu
bewahren gibt. Ein Fehlschlag von `StateStore.save`/`markCancelled` **nach**
einem erfolgreichen Versand ist ebenfalls nicht isoliert (siehe
`docs/use-cases.md`, Fehlerfälle) — dort bricht der restliche Zyklus ab, da
dieser Fall im Code nicht durch dieselbe `trySend`-artige Behandlung
abgesichert ist.

## Konsequenzen

- Ein dauerhaft fehlschlagender einzelner Quelltermin (z. B. strukturell
  fehlerhaft) blockiert nicht die Aktualisierungen und Absagen der übrigen
  Quelltermine desselben Zyklus — kein Kopf-an-der-Schlange-Problem.
- Es wird keine zusätzliche Retry-/Backoff-Infrastruktur benötigt: Der
  bereits vorhandene Poll-and-Diff-Mechanismus fungiert selbst als
  Wiederholungsmechanismus, da ein nicht in `StateStore` aktualisierter
  Quelltermin beim nächsten Poll erneut dieselbe Aktion auslöst.
- Ein dauerhaft fehlschlagender Quelltermin erzeugt bei jedem Zyklus erneut
  einen Eintrag in `failed`, ohne Deduplizierung oder Eskalation über
  mehrere Zyklen hinweg — das ist eine bewusst minimal gehaltene Lösung;
  Beobachtbarkeit über wiederholte Fehlschläge ist Aufgabe der
  konsumierenden Log-Auswertung, nicht dieses Service.
- Der Fehlerfall "erfolgreicher Versand, aber fehlgeschlagenes
  `StateStore`-Schreiben" ist bewusst **nicht** auf dieselbe Weise isoliert
  — er bricht den restlichen Zyklus ab. Diese Inkonsistenz zwischen den
  beiden Fehlerarten ist im Code vorhanden und in `docs/use-cases.md`
  dokumentiert; sie wurde für dieses ADR nicht rückwirkend "repariert",
  sondern als tatsächliches Verhalten festgehalten.
