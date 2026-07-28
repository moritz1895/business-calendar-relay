# ADR-007: Erstellungs-Filter wirkt ausschließlich als Gate für die Neuanlage

**Datum:** 2026-07-28
**Status:** Angenommen

## Kontext

Läuft der Service zum ersten Mal gegen einen Kalender mit mehrjähriger
Historie, würde die bisherige Poll-and-Diff-Logik jeden historischen Termin
als "neu" behandeln und für jeden einzelnen eine iMIP-Einladung
verschicken — ein Flood aus hunderten oder tausenden Mails (GitHub-Issue
#3). Ein Erstellungs-Filter (`RelayDiffPlanner.isEligibleForCreation`)
schließt Quelltermine mit Start in der Vergangenheit, ganztägige,
nicht-beschäftigte und stornierte Termine sowie — bei wiederkehrenden
Terminen — Vorkommen außerhalb eines konfigurierbaren
Wiederholungs-Zeitfensters von der Erstellung aus.

Naheliegend, aber falsch wäre gewesen, denselben Filter auch auf bereits
vorhandene `RelayState`-Einträge anzuwenden: Ein Termin, der beim ersten
Poll noch erstellungsberechtigt war und einen Blocker bekommen hat, altert
zwangsläufig irgendwann über den Vergangenheits-Cutoff hinaus, oder ein
wiederkehrendes Vorkommen kann aus dem Wiederholungs-Zeitfenster
herausfallen, sobald `now` weit genug fortschreitet. Würde der Filter auch
hier greifen, würde ein längst aktiver, real im Geschäftskalender
geblockter Termin bei einem der folgenden Polls unbemerkt storniert — ohne
dass sein Quelltermin tatsächlich aus dem Kalender verschwunden wäre.

## Entscheidung

Der Erstellungs-Filter wird ausschließlich für den Zweig "kein
`RelayState` vorhanden" von `RelayDiffPlanner.plan(...)` befragt. Für jeden
Quelltermin mit bereits vorhandenem `RelayState`-Eintrag — ob `active`
oder bereits storniert — hat der Filter keinerlei Einfluss; ein solcher
Quelltermin durchläuft unverändert die bestehenden Aktualisierungs-/
Keine-Aktion-/Absage-/Wiederauferstehungs-Regeln. Storniert wird ein
Blocker ausschließlich dann, wenn sein Quelltermin tatsächlich aus
`CalendarSource.readEvents()` verschwindet — niemals, weil er inzwischen in
der Vergangenheit liegt, auf ganztägig/nicht-beschäftigt/storniert
umgestellt wurde, oder aus dem Wiederholungs-Zeitfenster herausgefallen
ist. Ein nicht erstellungsberechtigter Quelltermin ohne `RelayState` wird
für den aktuellen Zyklus einfach übersprungen und beim nächsten Poll erneut
unvoreingenommen gegen den Filter (mit dann aktuellem `now`) geprüft — ohne
eigene Buchführung darüber, was in einem Zyklus übersprungen wurde.

Diese Trennung wurde bewusst als eigenständige, im Code wie in der
Spezifikation (`docs/features/event-filtering.md`) explizit
hervorgehobene Regel behandelt statt implizit aus der allgemeinen
Filterbeschreibung abgeleitet zu werden.

## Konsequenzen

- Ein einmal erstellter Blocker bleibt im Geschäftskalender bestehen,
  solange sein Quelltermin im Quellkalender sichtbar ist — unabhängig
  davon, ob er inzwischen in der Vergangenheit liegt, auf
  ganztägig/nicht-beschäftigt/storniert umgestellt wurde, oder ein
  wiederkehrendes Vorkommen aus dem Wiederholungs-Zeitfenster
  herausgefallen ist. Das ist die einzige Garantie, die verhindert, dass
  real aktive Blocker unbemerkt aus Outlook verschwinden.
- Eine nachträgliche `STATUS:CANCELLED`-Markierung an einem weiterhin im
  Read sichtbaren Quelltermin storniert dessen Blocker **nicht** aktiv —
  sie verhindert nur eine etwaige künftige Neuanlage. Das ist eine bewusste
  Entscheidung (mit dem Projektverantwortlichen bestätigt, siehe
  `docs/features/event-filtering.md`), keine Lücke.
- Wird `relay.recurring-event-horizon` nachträglich verkleinert, können
  bereits aktive, weiter in der Zukunft liegende wiederkehrende Vorkommen
  zwar keine neue Erstellung mehr auslösen, ihre bereits existierenden
  Blocker bleiben aber unangetastet, solange der Adapter sie weiterhin
  zurückliefert — akzeptiertes, seltenes Reconfiguration-Risiko, keine
  Code-Konsequenz.
- Der Filter und die (unabhängig davon erweiterte) Änderungserkennung sind
  zwei getrennte Mechanismen: Ein Termin, der nachträglich z. B. auf
  "nicht beschäftigt" umgestellt wird, kann trotzdem ein Update auslösen,
  obwohl er den Erstellungs-Filter nicht mehr bestehen würde — kein
  Widerspruch, da beide Mechanismen unterschiedliche Zweige von
  `RelayDiffPlanner.plan(...)` betreffen.
