# Feature: Event-Filterung für Bestandsdaten (Initiales Handling großer Kalenderhistorien)

GitHub-Issue #3 — "Initiales Handling von vielen Bestandsdaten". Diese Spec
erweitert `docs/features/relay-orchestration.md` (Poll-and-Diff-Orchestrierung)
um Regeln, die entscheiden, **welche** Source Events überhaupt als neuer
Blocker angelegt werden dürfen. Sie ändert nichts an der bereits spezifizierten
Diff-Logik selbst.

## Feature-Zusammenfassung

Läuft dieser Service zum ersten Mal gegen einen echten Kalender mit
mehrjähriger Historie, würde die heutige Poll-and-Diff-Logik jeden
historischen Termin als "neu" behandeln und für jeden einzelnen eine
iMIP-Einladung verschicken — ein Flood aus hunderten oder tausenden Mails.
Diese Feature führt eine **Erstellungs-Filterung** ein: ein Source Event darf
nur dann neu als Blocker angelegt werden, wenn sein Start in der Zukunft
liegt, es kein ganztägiger Termin ist, es im Quellkalender als "beschäftigt"
(nicht `TRANSP:TRANSPARENT`) markiert ist und — bei wiederkehrenden Terminen —
sein Termin innerhalb eines konfigurierbaren, nach vorne gleitenden
Zeitfensters liegt. Zusätzlich wird die bislang unbehandelte Auflösung von
`EXDATE` und `RECURRENCE-ID` bei wiederkehrenden Terminen eingeführt, damit
keine Phantom- oder Duplikat-Vorkommen entstehen.

## Wichtigste Regel — darf bei der Umsetzung nicht verloren gehen

> **Jede Filterregel in dieser Feature (Vergangenheits-Cutoff,
> Ganztägig-Ausschluss, Transparent-Ausschluss, Wiederholungs-Zeitfenster —
> und die unten selbst ergänzte `STATUS:CANCELLED`-Regel) wirkt
> ausschließlich als Gate für den Zweig "kein `RelayState` vorhanden → neu
> anlegen" von `RelayDiffPlanner.plan(...)`. Sie darf niemals dazu führen,
> dass ein bereits vorhandener `RelayState`-Eintrag (`active == true` **oder**
> `active == false`, also auch bereits stornierte Einträge) storniert oder
> anders behandelt wird, nur weil das Source Event nach diesen Regeln aktuell
> nicht mehr "matchen" würde.**

Konkret: Sobald zu einem `sourceUid` bereits ein `RelayState`-Eintrag
existiert — egal ob `active` oder bereits storniert —, durchläuft dieses
Source Event unverändert die bestehenden Update-/No-op-/Cancel-/
Resurrection-Regeln aus `relay-orchestration.md` (Schritte 3.2–3.4 und 4).
Der Filter wird dafür an dieser Stelle **überhaupt nicht befragt**. Storniert
wird ein Blocker weiterhin ausschließlich dann, wenn sein Source Event
tatsächlich aus `CalendarSource.readEvents()` verschwindet (Schritt 4 der
bestehenden Spec) — niemals, weil der Termin inzwischen in der Vergangenheit
liegt, auf ganztägig/transparent umgestellt wurde, oder aus dem
Wiederholungs-Zeitfenster herausgefallen ist. Ein Fehler an dieser Stelle
würde dazu führen, dass real aktive, gerade laufende Blocker im
Geschäftskalender unbemerkt verschwinden.

## Akteure

Unverändert gegenüber `relay-orchestration.md`: **Scheduler** ist der
einzige Akteur; es gibt keinen menschlichen Trigger für einen einzelnen
Poll-Zyklus.

## Use Case: Poll and Relay Source Calendar — Änderung an Schritt 3.1

Dies ist die einzige inhaltliche Änderung an der bestehenden Use-Case-Spec.
Alle anderen Schritte (Command, 3.2, 3.3, 3.4, 4, 5, Fehlerfälle, Result)
bleiben exakt wie in `relay-orchestration.md` beschrieben.

**Bisher (relay-orchestration.md, 3.1):**

> Not in `priorStates` → create. Generate a new `blockerUid` …

**Neu:**

> **Not in `priorStates` und erstellungsberechtigt (siehe
> `isEligibleForCreation` unten) → create**, exakt wie bisher beschrieben
> (neue `blockerUid`, `sequence = 0`, `REQUEST` rendern und senden, `RelayState`
> speichern).
>
> **Not in `priorStates` und *nicht* erstellungsberechtigt → keine Aktion.**
> Für dieses Source Event wird in diesem Zyklus weder gerendert noch
> gesendet noch ein `RelayState` angelegt. Es wird beim nächsten Poll erneut
> unvoreingenommen gegen den Filter geprüft — ändert sich sein
> Filter-Ergebnis (Start rückt ins Zeitfenster, Zeitfenster rutscht am
> Termin vorbei, `TRANSP` wird umgestellt, …), wird es dann automatisch
> berücksichtigt. Das ergibt sich allein daraus, dass der Filter bei jedem
> Zyklus frisch gegen das aktuelle "jetzt" ausgewertet wird — es braucht
> keine eigene "Zeitfenster ist vorgerückt"-Logik.

### Der Erstellungs-Filter (`isEligibleForCreation`)

Ein Source Event `e` ist zum Zeitpunkt `now` genau dann erstellungsberechtigt,
wenn **alle** folgenden Bedingungen zutreffen:

1. **Vergangenheits-Cutoff:** `!e.start().isBefore(now)`. Maßgeblich ist
   ausdrücklich `start`, nicht `end` — ein bereits laufender, aber noch nicht
   beendeter Termin (Start in der Vergangenheit, Ende in der Zukunft) gilt
   ebenfalls als nicht mehr erstellungsberechtigt. Das ist eine bewusste,
   vom Projektverantwortlichen bestätigte Konsequenz dieser Regel, keine
   Ungenauigkeit.
2. **Kein ganztägiger Termin:** `!e.allDay()`.
3. **Als "beschäftigt" markiert:** `e.busy()` (siehe unten — entspricht
   `TRANSP` ungleich `TRANSPARENT`).
4. **Nicht storniert markiert:** `!e.cancelled()` (siehe eigene Einschätzung
   unten).
5. **Wiederholungs-Zeitfenster, nur für wiederkehrende Termine:**
   `!e.recurring() || !e.start().isAfter(now.plus(recurringEventHorizon))`.
   Einzelne (nicht wiederkehrende) Termine haben laut Vorgabe **keine**
   obere Zeitschranke — nur der Vergangenheits-Cutoff (1) gilt für sie.

`now` und `recurringEventHorizon` sind frische Eingaben pro Poll-Zyklus,
keine beim Start einmalig berechneten Werte — siehe "Änderungen an
`RelayDiffPlanner`" unten.

## Domain model additions

### `SourceEvent` — neue Felder

`SourceEvent` bekommt vier zusätzliche boolesche Felder, jedes exakt für
eine Filterbedingung oben:

- `allDay` — `true`, wenn der Termin im Quellkalender ein ganztägiger Termin
  ist (`DTSTART`/`DTEND` mit `VALUE=DATE`, kein Zeitanteil), `false` sonst.
- `busy` — `true`, wenn der Termin im Quellkalender Zeit blockiert
  (`TRANSP:OPAQUE`, oder `TRANSP` fehlt — `OPAQUE` ist laut RFC 5545 der
  Default), `false` bei `TRANSP:TRANSPARENT`.
- `recurring` — `true`, wenn dieses Vorkommen aus einer wiederkehrenden
  Serie (`RRULE`) stammt, unabhängig davon, ob es selbst über
  `RECURRENCE-ID` überschrieben wurde. `false` bei einem echten
  Einzeltermin.
- `cancelled` — `true`, wenn das zugrunde liegende `VEVENT` (bzw. bei
  wiederkehrenden Serien: das Master-`VEVENT`) `STATUS:CANCELLED` trägt.
  Siehe "Weitere Filterregeln" unten für die genaue Herleitung und die
  Abgrenzung zu `EXDATE`/`RECURRENCE-ID`-Löschungen.

`sourceUid`, `start`, `end` bleiben unverändert in Bedeutung und Invarianten
(`end` nach `start`, gleiche Zeitzone) — mit einer Ergänzung für
wiederkehrende Termine, siehe nächster Abschnitt.

Bewusst **keine** eigene "Roh"- oder "gefilterte" Variante von `SourceEvent`
eingeführt: Der Filter selbst liegt außerhalb von `SourceEvent` (im
`RelayDiffPlanner`, s.u.), das Value Object trägt nur die Fakten, die der
Filter braucht. Ein zweiter paralleler Typ würde hier keinen Vorteil bringen,
nur eine zusätzliche Umwandlung zwischen Adapter und Domain-Schicht.

### Zusammengesetzter `sourceUid` für wiederkehrende Termine

Ein CalDAV-`UID` identifiziert die **Serie**, nicht das einzelne Vorkommen —
mehrere Vorkommen derselben Serie teilen sich dieselbe `UID`. Da
`RelayDiffPlanner` und `StateStore` pro `sourceUid` genau einen
Lebenszyklus (create → update → cancel) führen, braucht jedes einzelne
Vorkommen eine **eigene, stabile Identität**.

**Entscheidung:** Für ein Vorkommen aus einer wiederkehrenden Serie setzt
sich `sourceUid` zusammen aus:

```
<Serien-UID> + "#" + <ursprünglicher, serienberechneter Start dieses Vorkommens als Instant (UTC), ISO-8601>
```

Für einen echten Einzeltermin bleibt `sourceUid` unverändert die reine
`VEVENT`-`UID` (kein Suffix).

Wichtig: Der Suffix ist immer der **ursprüngliche, von der `RRULE`
berechnete** Zeitpunkt dieses Vorkommens (bei einem überschriebenen Vorkommen
also exakt der `RECURRENCE-ID`-Wert) — **nicht** die tatsächliche,
möglicherweise durch ein `RECURRENCE-ID`-Override verschobene Startzeit.
So bleibt die Identität eines Vorkommens stabil, selbst wenn es später auf
eine andere Uhrzeit verschoben wird — eine Verschiebung wird dadurch korrekt
als **Update** desselben `sourceUid` erkannt (gleicher `blockerUid`,
`sequence + 1`), statt als Cancel eines "verschwundenen" Vorkommens plus
Create eines vermeintlich neuen. Das ist konsistent mit dem bereits in
`relay-orchestration.md` etablierten Prinzip "ein `blockerUid` pro Source
Event über dessen gesamte Lebenszeit".

Die tatsächlichen (ggf. überschriebenen) Werte für `start`/`end` — die real
im Geschäftskalender geblockte Zeit — kommen unverändert aus dem
Override-`VEVENT`, falls eines existiert.

### Auflösung von `EXDATE` und `RECURRENCE-ID`

Diese Auflösung gehört an die **CalDAV-Adapter-Grenze**
(`CalDavCalendarSourceAdapter`), nicht in `core/domain`. Begründung: `RRULE`,
`EXDATE` und `RECURRENCE-ID` sind reine iCalendar-/CalDAV-Protokolldetails —
genau die Art von Wissen, die laut CLAUDE.md nicht in `core/domain` oder
`core/app` gehört. Der Adapter liefert weiterhin eine flache, bereits
vollständig aufgelöste Liste von `SourceEvent`s über den unveränderten
`CalendarSource`-Port; `RelayDiffPlanner` und der Rest der Domain-Schicht
sehen niemals `RRULE`, `EXDATE` oder `RECURRENCE-ID` — nur fertige
Vorkommen mit den oben beschriebenen Feldern.

Damit der Adapter das korrekt leisten kann, muss er (anders als heute) alle
CalDAV-Response-Ressourcen **zuerst nach `UID` gruppieren**, bevor er pro
Serie expandiert — heute verarbeitet
`CalDavCalendarSourceAdapter.readEvents()` jeden `calendar-data`-Blob
unabhängig (`for (var calendarData : extractCalendarDataBlobs(...))
{ events.addAll(parseVEvents(calendarData)); }`); Override-Komponenten
(gleiche `UID`, andere `RECURRENCE-ID`) können aber als eigene Ressource
neben dem Master zurückkommen und müssen vor der Expansion demselben
Master zugeordnet werden.

Pro `UID`-Gruppe (ein Master-`VEVENT` mit optionaler `RRULE`, `EXDATE` und
null oder mehr `RECURRENCE-ID`-Override-Komponenten) gilt:

- **Kein `RRULE` am Master:** einzelner Termin, unverändertes Verhalten von
  heute (ein `SourceEvent`, `recurring = false`).
- **`RRULE` vorhanden:** die Serie wird ausgehend vom Master expandiert
  (`ical4j` bietet dafür fertige Bausteine, z. B. `Recur`/
  `calculateRecurrenceSet`, sodass keine eigene RRULE-Implementierung nötig
  ist).
  - **Ein berechnetes Vorkommen, dessen Zeitpunkt in `EXDATE` steht**, wird
    **nicht** als `SourceEvent` ausgegeben — an dieser Stelle existiert
    schlicht kein Termin. Verschwindet dadurch nachträglich ein Vorkommen,
    für das bereits ein `RelayState` existiert (Nutzer löscht ein einzelnes
    Vorkommen einer bereits gespiegelten Serie nachträglich per `EXDATE`),
    ist das **keine neue Filter-Wirkung**, sondern exakt der bereits
    bestehende, absenz-basierte Cancel-Mechanismus aus Schritt 4 von
    `relay-orchestration.md` — das Vorkommen ist im Quellkalender
    tatsächlich nicht mehr vorhanden, also korrekt ein "echtes Verschwinden".
  - **Ein berechnetes Vorkommen, zu dem eine `RECURRENCE-ID`-Override-Komponente
    existiert**, wird durch genau **ein** `SourceEvent` ersetzt: `start`/`end`
    (und `allDay`/`busy`/`cancelled`, falls im Override individuell
    abweichend gesetzt) kommen vom Override-`VEVENT`, `sourceUid` bleibt der
    oben beschriebene zusammengesetzte Schlüssel basierend auf dem
    ursprünglichen (unveränderten) Serientermin. Es wird **niemals** sowohl
    das serienberechnete als auch das überschriebene Vorkommen ausgegeben —
    das wäre ein doppelter Blocker für denselben realen Termin.
  - **Trägt die Override-Komponente selbst `STATUS:CANCELLED`**, wird dieses
    eine Vorkommen komplett fallen gelassen (wie bei `EXDATE` — kein
    `SourceEvent`, kein Flag). Das ist der Standard-Weg, mit dem viele
    CalDAV-Clients das Löschen eines einzelnen Vorkommens einer Serie
    abbilden (funktional äquivalent zu `EXDATE`, nur anders kodiert); ein
    bereits aktiver `RelayState`-Eintrag für dieses eine Vorkommen wird
    dadurch — genau wie bei `EXDATE` — korrekt über den bestehenden
    Absenz-Mechanismus storniert, nicht über den Filter.
  - **Trägt der Master selbst `STATUS:CANCELLED`** (die ganze Serie wurde
    storniert, nicht nur ein Vorkommen), wird **nicht** die gesamte Serie
    aus der Ausgabe entfernt — das würde gegen die "wichtigste Regel" oben
    verstoßen und könnte bereits aktive `RelayState`-Einträge dieser Serie
    unbeabsichtigt zum Verschwinden bringen. Stattdessen wird `cancelled =
    true` auf **jedes** expandierte Vorkommen dieser Serie durchgereicht,
    und der übliche Erstellungs-Filter (Bedingung 4) sorgt dafür, dass ab
    diesem Zeitpunkt kein neues Vorkommen dieser Serie mehr angelegt wird —
    ohne bereits existierende Blocker anzufassen.
- **Ganztägige Vorkommen:** `DTSTART`/`DTEND` mit `VALUE=DATE` haben keinen
  Zeit- oder Zonenanteil, `SourceEvent.start`/`end` verlangen aber eine
  `ZonedDateTime` mit Zone (Invariante). Empfehlung: Mitternacht bis
  Mitternacht in der für den Adapter konfigurierten Standardzone verwenden.
  Das ist ohnehin nur für die Invariante relevant — solche Termine sind über
  `allDay = true` immer von der Erstellung ausgeschlossen, ihre exakte
  Uhrzeit spielt für dieses Feature keine Rolle.

**Zusätzlicher, beim Lesen des Ist-Codes gefundener Fehler, der mit diesem
Feature miterledigt werden muss:** `CalDavCalendarSourceAdapter.toZonedDateTime`
wirft heute für jedes `DTSTART`/`DTEND` ohne `TZID` und ohne UTC-Kennzeichnung
eine `CalDavCalendarSourceException` — das trifft exakt auf
`VALUE=DATE`-Werte ganztägiger Termine zu. Ein einziger ganztägiger Termin im
Quellkalender lässt `readEvents()` heute also komplett fehlschlagen und damit
den gesamten Poll-Zyklus abbrechen (siehe Fehlerfälle in
`relay-orchestration.md`). Die oben beschriebene explizite
`allDay`-Erkennung behebt das als Nebeneffekt — sie ist damit nicht nur eine
Filter-Notwendigkeit, sondern eine Bugfix-Voraussetzung.

### Änderungen an `RelayDiffPlanner`

`RelayDiffPlanner.plan(...)` bleibt eine reine Funktion ohne I/O, bekommt
aber zwei zusätzliche Eingaben, da der Filter frisch pro Zyklus ausgewertet
werden muss (Punkt 1 oben):

- `now` (`ZonedDateTime` oder `Instant`) — pro Aufruf übergeben, analog dazu,
  wie `PollAndRelaySourceCalendarService` bereits einen `Clock` hält und
  `clock.instant()` für `DTSTAMP` verwendet; derselbe Wert wird jetzt auch
  an den Planner weitergereicht.
- `recurringEventHorizon` (`java.time.Period`) — die konfigurierte
  Fenstergröße für wiederkehrende Termine (siehe Konfiguration unten). Ob
  das ein Konstruktor-Parameter des `RelayDiffPlanner` (einmalig konfiguriert)
  oder ein weiterer Aufrufparameter von `plan(...)` wird, ist Sache des
  Coder-Agents — beides ist mit "reine Funktion" vereinbar.

Der bestehende Zweig "kein `prior` → `Create`" (Zeile mit `if (prior ==
null)`) wird um die `isEligibleForCreation`-Prüfung ergänzt: nur wenn diese
zusätzlich `true` ist, wird eine `RelayAction.Create` erzeugt; sonst wird für
dieses Source Event in diesem Zyklus **gar keine** `RelayAction` erzeugt.
Alle anderen Zweige (`else if (!prior.active() || windowChanged(...))`,
sowie der komplette Cancel-Block über `priorByUid.values()`) bleiben exakt
wie heute — sie befragen den Filter an keiner Stelle, per Design.

## Weitere Filterregeln — eigene Einschätzung

Wie vom Auftraggeber gewünscht, hier eine Bewertung naheliegender
zusätzlicher Filter, jeweils mit Entscheidung und Begründung:

- **`STATUS:CANCELLED` am Source Event, aber noch von einem naiven
  CalDAV-Read zurückgegeben — Akzeptiert.** Manche CalDAV-Server/-Clients
  entfernen einen stornierten Termin nicht sofort aus der Kollektion,
  sondern belassen ihn mit `STATUS:CANCELLED`. Einen solchen Termin neu als
  Blocker anzulegen wäre offensichtlich falsch für den Zweck dieses
  Services (Zeit blocken, die tatsächlich gebraucht wird). Umgesetzt als
  neues Feld `cancelled` auf `SourceEvent`, geprüft in Bedingung 4 des
  Erstellungs-Filters — **ausschließlich Erstellungs-Gate**, exakt wie die
  vier vorgegebenen Regeln (Details und die Abgrenzung zu
  `EXDATE`/`RECURRENCE-ID` siehe oben). Ob ein bereits aktiver Blocker
  storniert werden soll, wenn sein Source Event *nachträglich* auf
  `STATUS:CANCELLED` wechselt, ohne aus dem Read zu verschwinden, ist
  **nicht** Teil dieser Entscheidung — siehe Open Questions.
- **Mindestdauer-Schwelle für sehr kurze/degenerierte Termine —
  Abgelehnt.** `SourceEvent`s Invariante (`end` muss nach `start` liegen)
  verhindert bereits echte Nulllängen-Termine strukturell. Für sehr kurze,
  aber gültige Termine (z. B. 1 Minute) gibt es keinen erkennbaren fachlichen
  Grund, sie auszuschließen: Sie markieren im Quellkalender ebenso
  "beschäftigt" wie jeder andere Termin, und der einzige Nachteil ist eine
  zusätzliche (aber harmlose) Mail. Eine Schwelle wäre zusätzliche, nicht
  angeforderte Konfigurationsfläche ohne belegten Bedarf — passend zu
  CLAUDE.md's "keine Hooks/Config-Fläche vor Bedarf aufbauen". Kann bei
  konkretem Leidensdruck später ergänzt werden.
- **`RDATE` (zusätzliche Einzeltermine einer Serie außerhalb der `RRULE`) —
  nicht gefordert, aber unschädlich falls miterledigt.** Vorgabe 7 nennt
  explizit nur `EXDATE` und `RECURRENCE-ID`; `RDATE` wird von dieser Spec
  nicht verlangt. Nutzt die gewählte Expansions-Implementierung (z. B.
  `ical4j`s `calculateRecurrenceSet`) `RDATE` ohnehin automatisch mit, spricht
  nichts dagegen, es einzubeziehen — es ist aber keine Anforderung dieser
  Feature und nicht gesondert zu testen.

## Port-Änderungen

### `CalendarSource`

**Die Methodensignatur `List<SourceEvent> readEvents()` ändert sich
nicht.** Es gibt keinen fachlichen Grund, "jetzt" oder das
Wiederholungs-Zeitfenster über den Port-Aufruf hereinzureichen — das wären
Filter-/Zeit-Konzepte, die laut Vertrag rein in der Lese-Verantwortung des
Ports nichts verloren haben. Stattdessen ändert sich, was der **Adapter**
zurückliefert (siehe "Auflösung von `EXDATE` und `RECURRENCE-ID`" oben) und
wie er intern konfiguriert wird:

- `CalDavCalendarSourceAdapter` bekommt zwei zusätzliche
  Konstruktor-Parameter, analog zu den bereits vorhandenen
  Pro-Kalender-Parametern (`calendarCollectionUri`, `username`, `password`):
  einen `Clock` (um "jetzt" für die Vorwärts-Deckelung frisch pro Aufruf zu
  bestimmen) und `recurringEventHorizon` (`Period`).
- **Vorwärts-Deckelung der Serien-Expansion:** Eine `RRULE` ohne `UNTIL`/
  `COUNT` kann unendlich viele Vorkommen erzeugen — reine
  Rechenbarkeit erzwingt eine technische Obergrenze für die Expansion nach
  vorne. Empfehlung: dieselbe `recurringEventHorizon` als technische
  Expansions-Obergrenze wiederverwenden (`now + recurringEventHorizon`),
  statt eine zweite, unabhängige Konfiguration einzuführen. Das ist
  korrekt, solange `recurringEventHorizon` konstant bleibt: Da `now` mit
  jedem Poll fortschreitet, rückt die Obergrenze bei jedem Zyklus weiter
  nach vorne — ein einmal innerhalb des Fensters expandiertes (und damit ggf.
  bereits erstelltes) Vorkommen fällt dadurch nie wieder aus der
  Adapter-Ausgabe heraus. Einzige Ausnahme: Wird `recurringEventHorizon`
  nachträglich in der Konfiguration **verkleinert**, könnten bereits aktive,
  weiter in der Zukunft liegende Vorkommen aus der Ausgabe fallen und fälschlich
  storniert werden — siehe Open Questions.
- **Keine Rückwärts-Deckelung.** Ebenso wie einzelne Termine schon heute
  ohne jede Zeitschranke gelesen werden, müssen auch vergangene Vorkommen
  einer Serie weiterhin unbegrenzt zurückgelesen werden — sonst würde ein
  bereits aktiver `RelayState`-Eintrag für ein inzwischen vergangenes
  Vorkommen beim nächsten Poll fälschlich als "verschwunden" erscheinen und
  storniert werden. Das ist exakt dasselbe Muster wie beim
  Vergangenheits-Cutoff-Filter selbst: Der Cutoff verhindert nur die
  *Neuanlage*, er darf niemals bewirken, dass der Adapter ein Vorkommen gar
  nicht erst zurückliefert.

### `BlockerSink`, `StateStore`

Unverändert. Der Filter wirkt ausschließlich zwischen `CalendarSource` und
`RelayDiffPlanner`; beide anderen Ports sehen von dieser Feature nichts.

### `PollAndRelaySourceCalendarUseCase` (inbound)

Unverändert (`pollAndRelay()` bleibt parameterlos). Die neue
`recurringEventHorizon`-Konfiguration ist — analog zu den bereits in
`relay-orchestration.md` als "additional inputs to this use case's
configuration" behandelten Organizer-/Attendee-/`From`-Adressen — ein
zusätzlicher Konfigurationswert für die Use-Case-Instanz, kein neuer
Command-Parameter.

### Konfiguration: `relay.recurring-event-horizon`

Empfehlung: `relay.recurring-event-horizon`, gebunden als `java.time.Period`
(Default `P6M`), **nicht** als `java.time.Duration`. Begründung: Spring
Boots `Duration`-Binding versteht nur elapsed-time-Einheiten (Sekunden,
Stunden, Tage) und kann "6 Monate" nicht korrekt ausdrücken — Monate haben
unterschiedliche Länge, und eine reine Elapsed-Time-Umrechnung (z. B. 6 × 30
Tage) würde über Zeitzonen-/Sommerzeit-Grenzen hinweg leicht ungenau. `Period`
ist dagegen ein datumsbasiertes Maß (Jahre/Monate/Tage) und bildet "6 Monate
ab jetzt" exakt ab. Spring Boot bindet `java.time.Period`-Properties bereits
von Haus aus aus ISO-8601-Period-Strings wie `P6M` — kein eigener Converter
nötig.

Wie im bestehenden `application.yml` (`relay.poll-interval` ist bereits ein
globaler, nicht pro-Kalender konfigurierter Wert) ist
`relay.recurring-event-horizon` als **ein globaler Wert für alle
konfigurierten Quellkalender** vorgesehen, kein Feld unter
`relay.calendars[]`.

## Out of scope

- **`sync-collection`-Delta-Sync (RFC 6578).** Unverändert deferred laut
  CLAUDE.md; diese Feature ändert nichts an `readEvents()`'s
  Vollständig-immer-Vertrag.
- **Inhaltsbasierte Filterung** (z. B. nach Titel/Stichwort). `SourceEvent`
  trägt weiterhin keine `SUMMARY`/`DESCRIPTION` — das ist kategorisch
  außerhalb dieser Feature, nicht nur "noch nicht gebaut", da es dem
  bestehenden Privacy-Design widersprechen würde (`CalendarSource` soll
  laut `relay-orchestration.md` nie mehr Kalenderinhalt offenlegen müssen,
  als für die Spiegelung nötig ist).
- **`VALARM`-Verarbeitung.** Unverändert außerhalb des Scopes.
- **Änderungen an `BlockerSink`- oder `StateStore`-Verträgen.** Beide bleiben
  unangetastet.
- **Pro-Kalender-Überschreibung der Filterregeln** (z. B. unterschiedliches
  `recurring-event-horizon` je Quellkalender, oder Abschalten einzelner
  Filterregeln für einen bestimmten Kalender). Alle Filterregeln gelten
  einheitlich für jeden konfigurierten Quellkalender; keine Override-Fläche.
- **Rückwirkende Zusammenfassung/Benachrichtigung über gefilterte
  Bestandsdaten.** Es wird kein "Katalog übersprungener historischer
  Termine" gebaut — sie werden einfach still nicht angelegt.
- **`RDATE`-Unterstützung als harte Anforderung** (siehe "Weitere
  Filterregeln" oben — unschädlich falls durch die gewählte
  Expansions-Implementierung ohnehin mit abgedeckt, aber nicht gefordert).

## Open questions

- **Soll ein bereits aktiver Blocker storniert werden, wenn sein Source
  Event nachträglich auf `STATUS:CANCELLED` wechselt, dabei aber weiterhin
  (mit diesem Status) von `CalendarSource.readEvents()` zurückgegeben wird —
  also *nicht* im Sinne des bestehenden Absenz-Mechanismus "verschwindet"?**
  Diese Spec entscheidet das bewusst nicht mit: Der bestehende Cancel-Zweig
  in `RelayDiffPlanner` basiert ausschließlich auf Abwesenheit aus
  `currentEvents`, nicht auf einem Status-Flag an einem weiterhin
  vorhandenen Vorkommen. `cancelled` hier zusätzlich als aktives
  Cancel-Signal zu verwenden wäre eine über die "Erstellungs-Gate-only"-Vorgabe
  hinausgehende, vom Auftraggeber nicht ausdrücklich freigegebene Erweiterung
  des Cancel-Zweigs selbst und sollte vor der Umsetzung explizit geklärt
  werden.
- **Was passiert, wenn `relay.recurring-event-horizon` nach dem ersten
  produktiven Lauf verkleinert wird?** Wie oben beschrieben, könnten dadurch
  bereits aktive Vorkommen weiter in der Zukunft aus der
  Adapter-Vorwärts-Deckelung herausfallen und fälschlich storniert werden.
  Diese Spec behandelt das als bekanntes, seltenes Reconfiguration-Risiko
  statt es architektonisch zu verhindern (z. B. über eine zusätzliche, vom
  Filter unabhängige, großzügigere technische Deckelung) — ob dieses Risiko
  akzeptabel ist oder ob eine zweite, bewusst großzügiger bemessene
  technische Obergrenze eingeführt werden soll, ist vor der Umsetzung zu
  klären.
- **Muss `RelayDiffPlanner` künftig auch bei No-op/Update-Vergleichen die
  neuen Felder (`allDay`, `busy`, `cancelled`) berücksichtigen, oder bleibt
  Änderungserkennung weiterhin ausschließlich `start`/`end`?** Diese Spec
  hält an `relay-orchestration.md`'s bestehender Entscheidung fest
  ("Change detection is `start`/`end` only") und rührt sie nicht an — ein
  Termin, der z. B. nachträglich auf `TRANSP:TRANSPARENT` umgestellt wird,
  löst also kein Update aus, solange sein Zeitfenster gleich bleibt. Das
  folgt konsequent aus der "wichtigste Regel" oben (Filter gilt nicht für
  bereits vorhandene `RelayState`-Einträge), wird hier aber als offene
  Frage benannt, falls das fachlich unerwünscht ist.
- **Zeitzonen-Behandlung ganztägiger Termine.** Diese Spec empfiehlt
  Mitternacht-zu-Mitternacht in der adapterseitig konfigurierten
  Standardzone, da ganztägige Termine ohnehin nie erstellungsberechtigt
  sind und die exakte Uhrzeit damit praktisch folgenlos ist — sollte sich
  das ändern (z. B. falls `allDay`-Ausschluss später gelockert wird), ist
  diese Wahl erneut zu prüfen.
