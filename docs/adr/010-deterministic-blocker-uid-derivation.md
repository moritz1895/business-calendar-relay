# ADR-010: Deterministische `blockerUid`-Ableitung aus `sourceUid`, statt zufällig (ersetzt ADR-002)

**Datum:** 2026-07-29
**Status:** Angenommen

## Kontext

`PollAndRelaySourceCalendarService.processCreate` verschickt die iMIP-
`REQUEST`-Mail über `BlockerSink.send`, **bevor** `StateStore.save` die
`sourceUid`→`blockerUid`-Zuordnung persistiert (Reihenfolge nötig, damit
`RelayState` nur bei tatsächlich erfolgreichem Versand entsteht). Schlägt
`save` danach fehl — DB-Fehler, Prozessabbruch mitten im Zyklus, volle
Platte — hat der Quelltermin beim nächsten Poll weiterhin keinen
`RelayState` und wird erneut unverändert als Erstellung diffed.

Mit der in ADR-002 getroffenen Entscheidung (`blockerUid` zufällig per
`UUID.randomUUID()`) erhielt ein solcher Retry eine völlig neue,
unabhängige `blockerUid`. Outlook erkennt eine `UID` als denselben Termin
nur bei exakter Übereinstimmung — der Retry kam also als zweite,
unabhängige Einladung an und erzeugte einen doppelten Blocker im
Business-Kalender ("Termine werden doppelt gesetzt").

## Entscheidung

`RelayDiffPlanner.deriveBlockerUid` leitet die `blockerUid` einer
Erstellungs-Aktion deterministisch aus der `sourceUid` ab
(`UUID.nameUUIDFromBytes(sourceUid.getBytes(UTF_8))`), statt eine
zufällige `UUID` zu erzeugen. Ein Retry derselben `sourceUid` — ob durch
einen fehlgeschlagenen `StateStore.save` oder durch das Draining-Restart-
Szenario aus ADR-008/ADR-009 — berechnet dadurch garantiert wieder exakt
dieselbe `blockerUid` bei `sequence = 0`, sodass Outlook den Resend als
denselben Termin erkennt statt einen zweiten anzulegen.

Die Ableitung bezieht bewusst keine Kalender-Identität ein und ist damit
nur innerhalb eines Quellkalenders eindeutig garantiert — ausreichend, da
`RelayDiffPlanner.plan(...)` ausschließlich pro bereits kalender-gescopter
`PollAndRelaySourceCalendarService`-Instanz aufgerufen wird und `plan(...)`
selbst keinen Kalender-Identitätsparameter trägt.

## Konsequenzen

- Ein nicht persistierter Erstellungs-Retry (`StateStore.save` schlägt nach
  erfolgreichem Versand fehl) ist jetzt sicher: derselbe Quelltermin
  erzeugt bei jedem Versuch dieselbe `blockerUid`, nie einen zweiten,
  unabhängigen Blocker.
- Diese Eigenschaft ist die tragende Voraussetzung für die Restart-
  Sicherheit des Initialisierungs-Rückstands (ADR-008): Ein Absturz
  zwischen `StateStore.save` und dem Entfernen der
  `PendingCreationQueue`-Zeile ist folgenlos genau deshalb, weil ein
  erneuter Versand ohnehin dieselbe `blockerUid` berechnen würde.
- Anders als in ADR-002 angenommen sickert der `UID`-Namensraum bzw. das
  Format des privaten Quellkalenders jetzt indirekt in die `blockerUid`
  durch (als Hash-Eingabe) — bewusst akzeptiert, da `UUID.nameUUIDFromBytes`
  eine Einwegfunktion ist: Aus der resultierenden `blockerUid` lässt sich
  die ursprüngliche `sourceUid` nicht zurückgewinnen, der praktische
  Datenschutz- und Entkopplungsvorteil aus ADR-002 bleibt also erhalten.
- Zwei unterschiedliche Quellkalender können weiterhin nicht kollidieren,
  solange ihre `sourceUid`-Namensräume sich nicht überschneiden — bei
  Überschneidung (z. B. beide von derselben CalDAV-Software erzeugt)
  würden sie jetzt dieselbe `blockerUid` ableiten. Dieses Risiko ist
  identisch mit der bereits in ADR-002 akzeptierten Grenze, dass
  `RelayDiffPlanner` keinen Kalender-Identitätsparameter trägt, und wird
  hier nicht neu bewertet.
