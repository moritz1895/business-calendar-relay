# ADR-002: Zufällige `blockerUid`-Generierung, unabhängig von `sourceUid`

**Datum:** 2026-07-28
**Status:** Angenommen

## Kontext

Jeder Blocker im dienstlichen Kalender braucht eine iMIP-`UID`, die über
seine gesamte Lebensdauer (Erstellung → Aktualisierungen → Absage) stabil
bleibt, damit Outlook aufeinanderfolgende Nachrichten als denselben Termin
erkennt statt als neue Einladungen. Diese `UID` muss bei der ersten
Erstellung eines Blockers irgendwie erzeugt werden. Eine naheliegende
Alternative wäre, sie deterministisch aus der `sourceUid` des
Quelltermins abzuleiten (z. B. per Hash oder direkter Wiederverwendung),
was den Bedarf an einer separaten Zuordnungstabelle theoretisch verringern
könnte.

## Entscheidung

`RelayDiffPlanner` generiert bei einer Erstellungs-Aktion eine neue
`blockerUid` **zufällig** (`UUID.randomUUID()`), vollständig unabhängig von
Wert und Format der `sourceUid`. Einmal vergeben, wird eine `blockerUid`
für die gesamte Lebensdauer ihres `RelayState`-Eintrags nie neu generiert —
auch nicht nach einer Absage und späteren Wiederauferstehung des
Quelltermins.

## Konsequenzen

- Der `UID`-Namensraum und das Format des privaten Quellkalenders sickern
  nie in die Identitäten des dienstlichen Kalenders durch — eine
  Datenschutz- und Entkopplungseigenschaft, die aus der Wahl folgt, nicht
  extra abgesichert werden muss.
- Die Zuordnung Quelltermin ↔ Blocker existiert ausschließlich explizit in
  `RelayState`/`StateStore`. Geht dieser Zustand verloren (siehe ADR-001),
  ist die Zuordnung nicht aus `sourceUid` und `blockerUid` allein
  rekonstruierbar — es gibt keinen Fallback-Ableitungspfad.
- Zwei unterschiedliche Quellkalender können niemals kollidierende
  `blockerUid`s erzeugen, selbst wenn ihre `sourceUid`-Namensräume sich
  überschneiden (z. B. beide von derselben CalDAV-Software erzeugt).
