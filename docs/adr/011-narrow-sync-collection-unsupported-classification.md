# ADR-011: Enge Klassifikation von "sync-collection nicht unterstützt", statt jeder unerwarteten Statuscode

**Datum:** 2026-07-30
**Status:** Angenommen

## Kontext

`CalDavCalendarSourceAdapter`s Delta-Sync-Mechanismus (RFC 6578
`sync-collection`, `docs/features/delta-sync.md`) muss bei jeder
Server-Antwort, die weder `207 Multi-Status` (Erfolg) noch der spezifisch
erkannte "Sync-Token ungültig"-Fall (`403 Forbidden` mit
`<D:valid-sync-token/>`-Precondition, oder `507 Insufficient Storage`) ist,
entscheiden, ob der CalDAV-Server `sync-collection` für diese Collection
grundsätzlich nicht unterstützt (→ dauerhafter, prozesslebenszeit-langer
Fallback auf die bisherige, stets vollständige `calendar-query`-Anfrage)
oder ob es sich um einen vorübergehenden, folgenlosen Fehler dieses einen
Poll-Zyklus handelt (→ nur dieser Zyklus schlägt fehl, der nächste Zyklus
versucht `sync-collection` erneut mit demselben, weiterhin gültigen
Sync-Token).

Die erste Implementierung (Commit `7026e45`) behandelte **jede** Antwort
außerhalb der beiden erkannten Erfolgs-/Ungültig-Token-Fälle als
"nicht unterstützt" und löste damit sofort den dauerhaften Fallback aus.
Das ist zu grob: Ein CalDAV-Server, der `sync-collection` normalerweise
korrekt unterstützt, aber einmalig mit einem transienten Fehler wie
`503 Service Unavailable` antwortet (Wartungsfenster, kurzzeitige
Überlastung), hätte diesen einen Ausrutscher fälschlich als dauerhaftes
"nicht unterstützt"-Signal gewertet — der betroffene Kalender wäre für den
Rest der Prozesslaufzeit unbemerkt und ohne jede Fehlermeldung auf den
langsameren, stets vollständigen Beschaffungsweg zurückgefallen, obwohl der
Server `sync-collection` tatsächlich weiterhin unterstützt. Dieser Fehler
wurde noch am selben Tag in Commit `e141ecc` korrigiert, bevor der PR
(#24) gemergt wurde.

## Entscheidung

`isDefinitelyUnsupportedResponse(...)` erkennt ausschließlich drei enge,
konkrete Signale als "sync-collection für diese Collection nicht
unterstützt":

- `501 Not Implemented`
- `415 Unsupported Media Type`
- `403 Forbidden` **ohne** die `<D:valid-sync-token/>`-Precondition (z. B.
  mit einer `<D:supported-report/>`-Precondition nach RFC 3253, oder ganz
  ohne erkennbaren Precondition-Body)

Nur bei einem dieser drei Fälle wird `deltaSyncPermanentlyDisabled` gesetzt
und der Adapter dauerhaft (In-Memory, nicht persistiert) auf
`calendar-query` umgeschaltet. Jede andere, nicht in dieses Set fallende
Statuscode-Antwort (z. B. `503 Service Unavailable`, `500 Internal Server
Error`, ein unerwarteter `4xx`) löst stattdessen eine einfache
`CalDavCalendarSourceException` aus, die nur den aktuellen Poll-Zyklus
scheitern lässt — der Sync-Token bleibt unverändert gültig, der nächste
Zyklus versucht `sync-collection` erneut mit genau diesem Token.

## Konsequenzen

- Ein vorübergehender Serverfehler (z. B. `503`) kostet höchstens einen
  einzelnen fehlgeschlagenen Poll-Zyklus (identisch zu jedem anderen
  fehlgeschlagenen `readEvents()`-Aufruf, siehe `docs/use-cases.md`) statt
  einer stillen, dauerhaften Degradierung auf den langsameren
  Beschaffungsweg für den Rest der Prozesslaufzeit.
- Das Risiko verschiebt sich in die andere Richtung, wird aber als
  ungefährlicher bewertet: Antwortet ein Server mit einem tatsächlich
  dauerhaften, aber nicht in diesem engen Set erfassten Fehlerstatus (z. B.
  ein exotischer `4xx`, den ein bestimmter Server statt `501`/`415`/`403`
  verwendet), wird `sync-collection` bei **jedem** Zyklus erneut versucht
  und schlägt jedes Mal erneut fehl, statt einmalig endgültig auf
  `calendar-query` umzuschalten — jeder betroffene Poll-Zyklus liefert in
  diesem Fall gar keine `SourceEvent`s und der Zyklus bricht vollständig
  ab, bis entweder der Server sein Verhalten ändert oder
  `delta-sync-enabled` für diesen Kalender manuell auf `false` gesetzt
  wird. Das wird als das kleinere Risiko bewertet, weil es sofort und
  wiederholt sichtbar im Log auftritt (statt sich unbemerkt als
  Performance-Regression zu tarnen) und weil der manuelle Notausschalter
  (`delta-sync-enabled: false`, siehe `docs/features/delta-sync.md`) für
  genau diesen Fall existiert.
- Die Unterscheidung "definitiv nicht unterstützt" vs. "unerwartet, aber
  möglicherweise transient" ist bewusst nicht erschöpfend im Sinne einer
  vollständigen RFC-6578-Fehler-Taxonomie, sondern eine pragmatische,
  konservative Untermenge: Nur Signale, die eindeutig und ausschließlich
  "dieser Report-Typ wird nicht unterstützt" bedeuten können, lösen den
  dauerhaften Fallback aus. Jede Serverantwort mit Mehrdeutigkeit zwischen
  "nicht unterstützt" und "vorübergehend gestört" wird zugunsten von
  "vorübergehend gestört" behandelt.
