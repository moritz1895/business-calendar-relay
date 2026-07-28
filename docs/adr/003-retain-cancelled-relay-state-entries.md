# ADR-003: Abgesagte `RelayState`-Einträge werden aufbewahrt, nicht gelöscht

**Datum:** 2026-07-28
**Status:** Angenommen

## Kontext

Sobald für einen Quelltermin eine Absage (`CANCEL`) versendet wurde, stellt
sich die Frage, was mit seinem `RelayState`-Eintrag geschehen soll. Ein
Löschen wäre die naheliegende, "aufräumende" Option und würde die
Datenmenge in `StateStore` klein halten. Zwei bereits im Projekt verankerte
Anforderungen sprechen jedoch dagegen: `SEQUENCE` muss für einen gegebenen
Blocker strikt steigen und darf nie zurückgesetzt werden (Nextcloud/Outlook
-Referenzbefunde), und das tatsächliche Entfernen eines abgesagten Termins
aus Outlook bleibt bewusst ein manueller Schritt, kein automatisiertes
Verhalten dieses Service.

## Entscheidung

`StateStore.markCancelled(sourceUid, sequence)` setzt `active = false` und
aktualisiert `sequence`, löscht den Eintrag aber nicht. Taucht derselbe
`sourceUid` in einem späteren Poll wieder auf, behandelt
`RelayDiffPlanner` einen nicht-aktiven vorherigen Zustand identisch zu
einem geänderten aktiven Zustand: Es entsteht eine Aktualisierung, die die
vorhandene `blockerUid` wiederverwendet und die `SEQUENCE`-Zählung
fortsetzt — keine neue, unabhängige Einladung.

## Konsequenzen

- Die `SEQUENCE`-Invarianz gilt garantiert über die gesamte Lebensdauer
  eines Quelltermins hinweg, auch über eine Absage und Wiederauferstehung
  hinweg — es gibt keinen Zustand, in dem die nächste `SEQUENCE`
  neu geraten oder auf `0` zurückgesetzt werden müsste.
- Ein wiederkehrender Quelltermin erzeugt in Outlook keine doppelte
  Einladung neben der (dort weiterhin als abgesagt sichtbaren) alten,
  sondern belebt dieselbe Blocker-`UID` wieder.
- `StateStore` wächst über die Zeit monoton, auch für Quelltermine, die
  nie wieder auftauchen — es gibt aktuell keinen Mechanismus, um wirklich
  endgültig verschwundene Einträge zu bereinigen. Das ist eine bewusst in
  Kauf genommene Konsequenz, kein übersehener Aufräumbedarf: Ein
  automatisches Löschen ließe sich nicht von einer echten
  Wiederauferstehung unterscheiden, ohne zusätzliche Aufbewahrungsfristen
  einzuführen, die es aktuell nicht gibt.
- Die Buchführung in `StateStore` "vergisst" einen abgesagten Termin nie,
  bevor seine Spur in Outlook tatsächlich manuell entfernt wurde — konsistent
  mit der Entscheidung, das Löschen in Outlook nicht zu automatisieren.
