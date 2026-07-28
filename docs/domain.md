# Domänenmodell

Dieses Dokument beschreibt das Domänenmodell von business-calendar-relay auf
fachlicher Ebene: die Wertobjekte, Domänendienste und Invarianten, die im
Quellcode unter `core/domain/` liegen. Implementierungsdetails der Adapter
(CalDAV, SMTP, Datenbank) sind bewusst nicht Teil dieses Dokuments — siehe
`docs/technical/` dafür.

## Überblick

Der fachliche Kern besteht ausschließlich aus unveränderlichen Wertobjekten
und zwei zustandslosen Domänendiensten. Es gibt keine Entität mit über die
Zeit veränderlicher Identität und keinen Aggregate Root im klassischen Sinn
— jede fachliche Entscheidung wird als neues, unveränderliches Objekt
ausgedrückt, nicht als Mutation eines bestehenden. Die drei zentralen
fachlichen Begriffe sind:

- **Source Event** — ein Termin, wie er im privaten Quellkalender existiert.
- **Blocker** — der gespiegelte, titellose Termin im dienstlichen
  Outlook-Kalender.
- **Relay** — die über die Zeit stabile Zuordnung zwischen einem Source
  Event und seinem Blocker, samt der iMIP-Versionierung (`SEQUENCE`), die
  Outlook erlaubt, aufeinanderfolgende Nachrichten als denselben Termin zu
  erkennen.

## Wertobjekte (Value Objects)

### `SourceEvent`

Ein CalDAV-`VEVENT`, wie es aus dem privaten Quellkalender gelesen wurde,
noch ohne jede Relay-Identität.

| Feld | Bedeutung |
|---|---|
| `sourceUid` | Die CalDAV-`UID` des Quelltermins. Eigener Namensraum, getrennt von der `UID` des Blockers. |
| `start`, `end` | Das Zeitfenster des Termins. |

`SourceEvent` trägt bewusst keine weiteren Informationen — keinen Titel,
keine Beschreibung, keinen Organisator, keine Teilnehmer. Da Blocker
titellos sind und Filterlogik (welche Quelltermine überhaupt gespiegelt
werden) bewusst noch nicht existiert, werden aktuell auch keine weiteren
Fakten über einen Quelltermin benötigt. Das ist zugleich eine
Datenschutzeigenschaft: Der Quellkalender-Port muss nie mehr über einen
Termin preisgeben, als für die Blocker-Erzeugung nötig ist.

### `BlockerEvent`

Ein einzelnes Blocker-Vorkommen, bereit zur Umwandlung in iMIP/ICS-Text.

| Feld | Bedeutung |
|---|---|
| `uid` | Die iMIP-`UID` des Blockers. Bleibt über Erstellung, Aktualisierungen und Absage desselben Quelltermins hinweg stabil. |
| `sequence` | Die iMIP-`SEQUENCE` dieser Version des Blockers. Wird vom Aufrufer (nicht vom Blocker selbst) vergeben und muss bei jeder erneuten Fassung derselben logischen Revision strikt steigen. |
| `start`, `end` | Das Zeitfenster, das im Business-Kalender geblockt wird. |
| `organizerEmail` | Die Organisator-Adresse des Blockers. |
| `attendeeEmail` | Die Adresse des dienstlichen Postfachs, das als Teilnehmer eingetragen wird. |

### `RelayState`

Der zuletzt bekannte Relay-Zustand eines Quelltermins — die fachliche
Buchführung, die eine Zuordnung Quelltermin → Blocker über mehrere
Poll-Zyklen hinweg aufrechterhält.

| Feld | Bedeutung |
|---|---|
| `sourceUid` | Der Quelltermin, auf den sich dieser Zustand bezieht (Schlüssel). |
| `blockerUid` | Die stabile Blocker-`UID` über die gesamte Lebensdauer des Quelltermins (Erstellung → Aktualisierungen → Absage). |
| `sequence` | Die zuletzt tatsächlich versendete `SEQUENCE` für `blockerUid` — die einzige Quelle der Wahrheit, aus der die nächste `SEQUENCE` abgeleitet wird. |
| `lastKnownStart`, `lastKnownEnd` | Das Zeitfenster des Quelltermins zum Zeitpunkt des letzten erfolgreichen Versands — die Vergleichsbasis für Änderungserkennung im nächsten Poll-Zyklus. |
| `active` | `true`, solange der Quelltermin noch vorhanden und nicht abgesagt ist; `false`, sobald eine Absage (`CANCEL`) versendet wurde. |

Ein `RelayState`-Eintrag mit `active = false` wird **nicht gelöscht**,
sondern bewusst aufbewahrt (siehe Domänenregeln unten).

### `RelayAction` (`Create`, `Update`, `Cancel`)

Eine einzelne Erstellungs-, Aktualisierungs- oder Absage-Entscheidung für
genau einen Quelltermin, wie sie `RelayDiffPlanner` für einen Poll-Zyklus
trifft. `RelayAction` ist eine versiegelte Schnittstelle mit drei
Ausprägungen, die alle dieselben Felder tragen (`sourceUid`, `blockerUid`,
`sequence`, `start`, `end`):

- **`Create`** — für einen Quelltermin ohne vorherigen `RelayState`: ein
  neuer Blocker muss unter einer frisch generierten `blockerUid` bei
  `sequence = 0` erstellt werden.
- **`Update`** — für einen Quelltermin, dessen Blocker (erneut) angefordert
  werden muss: entweder weil sich sein Zeitfenster geändert hat, während er
  aktiv war, oder weil er aus einem zuvor abgesagten Zustand
  "wiederaufersteht". Verwendet die vorhandene `blockerUid` bei
  `prior.sequence() + 1`.
- **`Cancel`** — für einen zuvor aktiven Quelltermin, der im aktuellen Poll
  nicht mehr vorhanden ist: der Blocker muss abgesagt werden, unter
  Wiederverwendung der vorhandenen `blockerUid` bei `prior.sequence() + 1`
  und dem zuletzt bekannten Zeitfenster (da kein aktuelles Fenster mehr
  existiert).

`RelayAction` trägt bewusst keine Angabe zur iMIP-Methode (`REQUEST`/
`CANCEL`) oder zu einem port-spezifischen Typ — das ist eine reine
Domänenentscheidung; die Übersetzung in Rendering, Versand und
Persistierung obliegt der Anwendungsschicht.

## Domänendienste (Domain Services)

### `RelayDiffPlanner`

Zustandsloser Dienst, der für einen Poll-Zyklus entscheidet, welche
Quelltermine einen neuen, aktualisierten oder abgesagten Blocker benötigen,
und welche `SEQUENCE` diese Aktion erhält. Reine Funktion ihrer zwei
Eingaben (`currentEvents`, `priorStates`) — führt selbst keine
Ein-/Ausgabe durch und kennt keinen Port. Die vollständigen
Entscheidungsregeln stehen unten unter "Domänenregeln".

### `ImipCalendarRenderer`

Zustandsloser Dienst, der `BlockerEvent`-Instanzen in rohen
iCalendar-Text (`BEGIN:VCALENDAR ... END:VCALENDAR`) für die beiden
unterstützten iMIP-Methoden (`REQUEST` für Erstellung/Aktualisierung,
`CANCEL` für Absage) übersetzt. Fachlich relevante Eigenschaften:

- Der `SUMMARY`-Wert ist immer das feste Literal `"Privater Blocker"` — nie
  vom Quelltermin abgeleitet. Das ist die technische Umsetzung des
  Titellos-Prinzips.
- Der Zeitstempel für `DTSTAMP` (`generatedAt`) wird dem Renderer vom
  Aufrufer übergeben, nicht selbst von einer Uhr gelesen — das hält den
  Domänendienst rein und deterministisch testbar.
- Bei einer Absage bleibt `STATUS:CONFIRMED` auf dem `VEVENT` erhalten; die
  Absage-Semantik liegt vollständig in `METHOD:CANCEL` auf
  `VCALENDAR`-Ebene.

## Domänenregeln (Invarianten)

### Validierungsregeln der Wertobjekte

Jedes Wertobjekt erzwingt seine Invarianten im kompakten Konstruktor —
ein ungültiger Zustand kann nicht einmal vorübergehend entstehen:

- `SourceEvent`, `BlockerEvent`, `RelayState` sowie jede `RelayAction`-Ausprägung:
  `end` muss nach `start` liegen; `start` und `end` müssen dieselbe Zeitzone
  verwenden.
- Alle `UID`-artigen Felder (`sourceUid`, `blockerUid`, `uid`) dürfen nicht
  leer oder nur aus Leerzeichen bestehen.
- `sequence` darf niemals negativ sein.
- `organizerEmail`/`attendeeEmail` (auf `BlockerEvent`) müssen eine
  `mailto`-taugliche Adresse enthalten (mindestens ein `@`).

### Fachliche Regeln der Poll-and-Relay-Entscheidung

Diese Regeln sind in `RelayDiffPlanner.plan(...)` implementiert und bilden
den fachlichen Kern des gesamten Service:

1. **Neuer Quelltermin → Erstellung.** Ein Quelltermin ohne vorherigen
   `RelayState` erhält eine neu generierte `blockerUid` und startet bei
   `sequence = 0`.
2. **Aktiver Quelltermin mit geändertem Zeitfenster → Aktualisierung.**
   Erkannt wird eine Änderung ausschließlich am Vergleich `start`/`end`
   gegen `lastKnownStart`/`lastKnownEnd` — kein anderes Feld wird
   verglichen, da `SourceEvent` keine weiteren Felder trägt.
3. **Aktiver Quelltermin mit unverändertem Zeitfenster → keine Aktion.**
   Ein erneutes Versenden einer identischen Anfrage würde Outlooks Zustand
   nicht ändern und nur unnötigen Mailverkehr sowie `SEQUENCE`-Verbrauch
   erzeugen.
4. **Zuvor abgesagter Quelltermin, der wieder vorhanden ist →
   Aktualisierung ("Wiederauferstehung").** Das ist kein eigener Codepfad,
   sondern ergibt sich zwangsläufig daraus, dass abgesagte
   `RelayState`-Einträge aufbewahrt statt gelöscht werden: Ein
   `RelayState` mit `active = false` wird identisch zu einem geänderten
   aktiven Zustand behandelt und erhält eine Aktualisierung, unabhängig
   davon, ob sich das Zeitfenster geändert hat.
5. **Vorher aktiver Quelltermin, der im aktuellen Poll fehlt → Absage.**
   Die vorhandene `blockerUid` wird bei `prior.sequence() + 1`
   wiederverwendet, mit dem zuletzt bekannten Zeitfenster.

### `SEQUENCE` muss strikt steigen und darf nie zurückgesetzt werden

Für eine gegebene `blockerUid` steigt `sequence` bei jeder Aktualisierung
oder Absage exakt um eins gegenüber dem zuletzt gespeicherten Wert
(`prior.sequence() + 1`) und wird niemals wiederverwendet oder verringert.
Diese Invariante gilt für die gesamte Lebensdauer eines Blockers, auch über
eine Absage und eine spätere Wiederauferstehung hinweg — genau deshalb
werden abgesagte Einträge nicht gelöscht (siehe unten). `RelayState` ist
die einzige Quelle der Wahrheit für den zuletzt gesendeten Wert.

### `blockerUid` wird zufällig generiert, unabhängig von `sourceUid`

Bei einer Erstellung wird eine neue `blockerUid` zufällig erzeugt (UUID),
unabhängig von Wert und Format der `sourceUid`. Eine deterministische
Ableitung (z. B. per Hash aus `sourceUid`) wurde bewusst nicht gewählt, da
sie den `UID`-Namensraum bzw. das Format des privaten Quellkalenders in die
Identitäten des Business-Kalenders durchsickern ließe — ohne fachlichen
Nutzen, da `RelayState` die Zuordnung ohnehin bereits explizit und
dauerhaft hält. Einmal vergeben, wird eine `blockerUid` für die gesamte
Lebensdauer ihres `RelayState`-Eintrags nie neu generiert, abgesagt oder
nicht.

### Abgesagte `RelayState`-Einträge werden aufbewahrt, nicht gelöscht

Ein Eintrag, für den eine Absage (`CANCEL`) versendet wurde, bleibt mit
`active = false` bestehen, statt gelöscht zu werden. Das hat zwei
zusammenhängende fachliche Gründe:

- **Erhalt der `SEQUENCE`-Invariante.** Würde der Eintrag gelöscht und der
  Quelltermin später erneut auftauchen, gäbe es nur zwei Optionen: den
  letzten `sequence`-Wert an anderer Stelle vorhalten (Duplizierung dessen,
  was `RelayState` bereits leistet) oder bei `0` unter einer neuen
  `blockerUid` neu beginnen — letzteres würde eine zweite, unabhängige
  Einladung in Outlook erzeugen statt die abgesagte wiederzubeleben, also
  einen doppelten Blocker. Das Aufbewahren ist der einzige Weg,
  garantiert zu verhindern, dass `SEQUENCE` je zurückgesetzt wird.
- **Konsistenz mit der Absage-Semantik.** Das tatsächliche Löschen eines
  abgesagten Termins aus Outlook bleibt bewusst ein manueller Schritt
  (siehe unten). Würde der `RelayState`-Eintrag im selben Moment gelöscht,
  in dem die Absage versendet wird, würde die Buchführung den Termin
  vergessen, bevor seine Outlook-seitige Spur tatsächlich verschwunden ist.

### Blocker sind titellos by design

`ImipCalendarRenderer` setzt `SUMMARY` immer auf das feste Literal
`"Privater Blocker"`. Kein Feld des Quelltermins beeinflusst diesen Wert —
nur die Zeitfensterinformation (Frei/Belegt) wird gespiegelt, niemals der
fachliche Anlass. Diese Regel ist untrennbar mit `SourceEvent`s bewusst
minimalem Umfang verbunden: Da `SourceEvent` ohnehin keinen Titel trägt,
kann ein Blocker gar nicht anders als titellos gerendert werden.

### Änderungserkennung erfolgt ausschließlich über Start/Ende

Ein Quelltermin gilt als "geändert" gegenüber seinem letzten bekannten
Zustand ausschließlich dann, wenn `start` oder `end` vom gespeicherten
`lastKnownStart`/`lastKnownEnd` abweicht. Da `SourceEvent` kein weiteres
Feld trägt und Blocker titellos sind, gibt es fachlich nichts anderes, das
sich ändern könnte. Diese Regel muss überprüft werden, sobald die
(aktuell bewusst zurückgestellte) Filterlogik `SourceEvent` künftig um
weitere vergleichbare Felder erweitert.

## Domänenausnahmen

Der Domänenkern (`core/domain/`) definiert **keine eigenen
Ausnahmetypen**. Ungültige Zustände werden ausschließlich über
Wächterklauseln in den kompakten Konstruktoren der Wertobjekte verhindert,
die bei Verstoß eine `IllegalArgumentException` (ungültiger Wert, z. B.
`end` nicht nach `start`) oder eine `NullPointerException` (fehlender
Pflichtwert) auslösen. Es gibt damit keinen Zustand, in dem ein
`SourceEvent`, `BlockerEvent`, `RelayState` oder `RelayAction` mit
verletzter Invariante existieren könnte — die Prüfung erfolgt vollständig
bei der Objekterzeugung, nicht nachträglich.

Ausnahmen, die eine fachliche Aktion scheitern lassen können (z. B. ein
fehlgeschlagener Versand), gehören zur Vertragsebene der ausgehenden Ports
(`ports/outbound/`) und sind entsprechend im Use-Case-Katalog
(`docs/use-cases.md`) beschrieben, nicht hier — sie sind kein
Domänenzustand, sondern eine Fehlermeldung der Außenwelt an die
Anwendungsschicht.
