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
| `sourceUid` | Die CalDAV-`UID` des Quelltermins. Eigener Namensraum, getrennt von der `UID` des Blockers. Für ein Vorkommen aus einer wiederkehrenden Serie ein zusammengesetzter Schlüssel — siehe "Zusammengesetzter `sourceUid` für wiederkehrende Termine" unten. |
| `start`, `end` | Das Zeitfenster des Termins. |
| `allDay` | `true`, wenn der Termin im Quellkalender ein ganztägiger Termin ist, `false` sonst. Speist ausschließlich den Erstellungs-Filter (siehe unten) sowie die Änderungserkennung — ganztägige Termine sind nie erstellungsberechtigt. |
| `busy` | `true`, wenn der Termin im Quellkalender Zeit blockiert (nicht als `TRANSP:TRANSPARENT` markiert ist), `false` sonst. |
| `recurring` | `true`, wenn dieses Vorkommen aus einer wiederkehrenden Serie stammt, unabhängig davon, ob es individuell überschrieben wurde. Rein informationell: fließt in den Erstellungs-Filter (Wiederholungs-Zeitfenster) ein, wird aber nie für Änderungserkennung verglichen. |
| `cancelled` | `true`, wenn der zugrunde liegende Termin (bzw. bei einer Serie: deren Master) als storniert markiert ist. |

`SourceEvent` trägt bewusst weiterhin keine inhaltlichen Informationen —
keinen Titel, keine Beschreibung, keinen Organisator, keine Teilnehmer. Die
vier booleschen Felder oben tragen ausschließlich Fakten, die der
Erstellungs-Filter und die Änderungserkennung brauchen (siehe
"Domänenregeln" unten), keine inhaltliche Erweiterung. Das bleibt zugleich
eine Datenschutzeigenschaft: Der Quellkalender-Port muss nie mehr über
einen Termin preisgeben, als für die Blocker-Erzeugung und die
Filterentscheidung nötig ist.

#### Zusammengesetzter `sourceUid` für wiederkehrende Termine

Eine CalDAV-`UID` identifiziert die gesamte Serie, nicht das einzelne
Vorkommen — mehrere Vorkommen derselben Serie teilen sich dieselbe `UID`.
Da `RelayDiffPlanner` und `StateStore` pro `sourceUid` genau einen
Lebenszyklus (Erstellung → Aktualisierung → Absage) führen, braucht jedes
Vorkommen eine eigene, stabile Identität. Für ein Vorkommen aus einer
wiederkehrenden Serie setzt sich `sourceUid` deshalb zusammen aus der
Serien-`UID`, einem `#`-Trennzeichen und dem ursprünglichen, von der Serie
berechneten Start dieses Vorkommens (nicht der ggf. durch eine individuelle
Verschiebung abweichenden tatsächlichen Startzeit). Für einen echten
Einzeltermin bleibt `sourceUid` unverändert die reine `VEVENT`-`UID`.

Der entscheidende Grund für "ursprünglicher, serienberechneter Zeitpunkt"
statt "tatsächlicher Zeitpunkt": Wird ein einzelnes Vorkommen später auf
eine andere Uhrzeit verschoben, bleibt seine Identität stabil — die
Verschiebung wird dadurch korrekt als Aktualisierung desselben `sourceUid`
erkannt (gleiche `blockerUid`, `sequence + 1`), statt als Absage eines
scheinbar verschwundenen Vorkommens plus Neuanlage eines vermeintlich neuen.
Das ist konsistent mit dem Prinzip "ein `blockerUid` pro Quelltermin über
dessen gesamte Lebenszeit".

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
| `lastKnownStart`, `lastKnownEnd` | Das Zeitfenster des Quelltermins zum Zeitpunkt des letzten erfolgreichen Versands — Teil der Vergleichsbasis für Änderungserkennung im nächsten Poll-Zyklus. |
| `active` | `true`, solange der Quelltermin noch vorhanden und nicht abgesagt ist; `false`, sobald eine Absage (`CANCEL`) versendet wurde. |
| `lastKnownAllDay`, `lastKnownBusy`, `lastKnownCancelled` | Der `allDay`-, `busy`- bzw. `cancelled`-Stand des Quelltermins zum Zeitpunkt des letzten erfolgreichen Versands — zusammen mit `lastKnownStart`/`lastKnownEnd` die vollständige Vergleichsbasis für Änderungserkennung (siehe Domänenregeln unten). |

Ein `RelayState`-Eintrag mit `active = false` wird **nicht gelöscht**,
sondern bewusst aufbewahrt (siehe Domänenregeln unten).

### `RelayAction` (`Create`, `Update`, `Cancel`)

Eine einzelne Erstellungs-, Aktualisierungs- oder Absage-Entscheidung für
genau einen Quelltermin, wie sie `RelayDiffPlanner` für einen Poll-Zyklus
trifft. `RelayAction` ist eine versiegelte Schnittstelle mit drei
Ausprägungen, die alle die Felder `sourceUid`, `blockerUid`, `sequence`,
`start`, `end` tragen; `Create` und `Update` tragen zusätzlich `allDay`,
`busy` und `cancelled` vom auslösenden `SourceEvent`, damit die
Anwendungsschicht nach erfolgreichem Versand die `lastKnown*`-Felder des zu
speichernden `RelayState` befüllen kann, ohne den Quelltermin erneut zu
lesen:

- **`Create`** — für einen Quelltermin ohne vorherigen `RelayState`, der
  zusätzlich den Erstellungs-Filter besteht (siehe Domänenregeln unten): ein
  neuer Blocker muss unter einer frisch generierten `blockerUid` bei
  `sequence = 0` erstellt werden.
- **`Update`** — für einen Quelltermin, dessen Blocker (erneut) angefordert
  werden muss: entweder weil sich sein Zeitfenster oder eines von `allDay`/
  `busy`/`cancelled` geändert hat, während er aktiv war, oder weil er aus
  einem zuvor abgesagten Zustand "wiederaufersteht". Verwendet die
  vorhandene `blockerUid` bei `prior.sequence() + 1`.
- **`Cancel`** — für einen zuvor aktiven Quelltermin, der im aktuellen Poll
  nicht mehr vorhanden ist: der Blocker muss abgesagt werden, unter
  Wiederverwendung der vorhandenen `blockerUid` bei `prior.sequence() + 1`
  und dem zuletzt bekannten Zeitfenster (da kein aktuelles Fenster mehr
  existiert). Trägt bewusst kein `allDay`/`busy`/`cancelled` — eine Absage
  braucht keinen `lastKnown*`-Stand mehr, da der `RelayState`-Eintrag danach
  nur noch auf `active = false` gesetzt, aber nicht mit neuen Werten
  überschrieben wird.

`RelayAction` trägt bewusst keine Angabe zur iMIP-Methode (`REQUEST`/
`CANCEL`) oder zu einem port-spezifischen Typ — das ist eine reine
Domänenentscheidung; die Übersetzung in Rendering, Versand und
Persistierung obliegt der Anwendungsschicht.

## Domänendienste (Domain Services)

### `RelayDiffPlanner`

Zustandsloser Dienst, der für einen Poll-Zyklus entscheidet, welche
Quelltermine einen neuen, aktualisierten oder abgesagten Blocker benötigen,
und welche `SEQUENCE` diese Aktion erhält. Reine Funktion ihrer vier
Eingaben (`currentEvents`, `priorStates`, `now`, `recurringEventHorizon`) —
führt selbst keine Ein-/Ausgabe durch und kennt keinen Port. `now` und
`recurringEventHorizon` werden pro Aufruf frisch übergeben, nicht als
Zustand gehalten, da der Erstellungs-Filter, den sie speisen, bei jedem
Poll-Zyklus gegen den aktuellen Zeitpunkt neu ausgewertet werden muss. Die
vollständigen Entscheidungsregeln stehen unten unter "Domänenregeln".

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

1. **Neuer, erstellungsberechtigter Quelltermin → Erstellung.** Ein
   Quelltermin ohne vorherigen `RelayState` erhält eine neu generierte
   `blockerUid` und startet bei `sequence = 0` — aber nur, wenn er zusätzlich
   den Erstellungs-Filter besteht (siehe eigener Abschnitt unten). Besteht er
   ihn nicht, wird für diesen Zyklus gar keine Aktion erzeugt; der Quelltermin
   wird beim nächsten Poll erneut unvoreingenommen gegen den Filter geprüft.
2. **Aktiver Quelltermin mit geändertem Zeitfenster oder geänderten Flags →
   Aktualisierung.** Erkannt wird eine Änderung am Vergleich `start`, `end`,
   `allDay`, `busy` und `cancelled` gegen die jeweiligen `lastKnown*`-Felder
   — weicht mindestens eines der fünf ab, wird aktualisiert. `recurring`
   fließt nicht in diesen Vergleich ein (siehe unten).
3. **Aktiver Quelltermin ohne Abweichung in einem der fünf Felder → keine
   Aktion.**
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

### Erstellungs-Filter — Gate ausschließlich für die Neuanlage

Ein Quelltermin ohne vorherigen `RelayState` wird nur dann tatsächlich als
neuer Blocker angelegt, wenn er **alle** folgenden Bedingungen erfüllt
(`RelayDiffPlanner.isEligibleForCreation`, ausgewertet frisch pro
Poll-Zyklus gegen das aktuelle `now`):

1. **Vergangenheits-Cutoff:** `start` liegt nicht in der Vergangenheit.
   Maßgeblich ist ausdrücklich `start`, nicht `end` — ein bereits laufender,
   aber noch nicht beendeter Termin gilt ebenfalls als nicht mehr
   erstellungsberechtigt. Das ist eine bewusste, keine ungenaue Konsequenz
   dieser Regel.
2. **Kein ganztägiger Termin:** `allDay` ist `false`.
3. **Als "beschäftigt" markiert:** `busy` ist `true`.
4. **Nicht storniert markiert:** `cancelled` ist `false`.
5. **Wiederholungs-Zeitfenster, nur für wiederkehrende Termine:** Ist
   `recurring` `true`, darf `start` nicht später liegen als
   `now.plus(recurringEventHorizon)` (konfigurierbar, siehe README).
   Einzeltermine haben keine solche obere Zeitschranke — für sie gilt nur
   der Vergangenheits-Cutoff aus Bedingung 1.

**Die wichtigste Invariante dieser Feature: Der Erstellungs-Filter wirkt
ausschließlich als Gate für die Neuanlage und wird für keinen Quelltermin
befragt, zu dem bereits ein `RelayState`-Eintrag existiert — weder für
einen aktiven noch für einen bereits abgesagten.** Ein Quelltermin mit
vorhandenem `RelayState` durchläuft unverändert die Aktualisierungs-/
Keine-Aktion-/Absage-/Wiederauferstehungs-Regeln oben, unabhängig davon, ob
er aktuell den Filter bestehen würde. Storniert wird ein Blocker weiterhin
ausschließlich dann, wenn sein Quelltermin tatsächlich aus
`CalendarSource.readEvents()` verschwindet — niemals, weil er inzwischen in
der Vergangenheit liegt, auf ganztägig/nicht-beschäftigt/storniert
umgestellt wurde oder aus dem Wiederholungs-Zeitfenster herausgefallen ist.
Ein Fehler an dieser Stelle würde dazu führen, dass real aktive, gerade
laufende Blocker im Geschäftskalender unbemerkt verschwinden — siehe
`docs/features/event-filtering.md` für die vollständige Herleitung dieser
Regel.

Ein nicht erstellungsberechtigter Quelltermin ohne `RelayState` wird für den
aktuellen Zyklus schlicht übersprungen — kein Rendern, kein Versand, kein
`RelayState`-Eintrag. Er wird beim nächsten Poll erneut unvoreingenommen
gegen den Filter geprüft; ändert sich sein Filter-Ergebnis (Start rückt ins
Zeitfenster, `TRANSP` wird umgestellt, …), wird er automatisch
berücksichtigt, ohne dass es dafür eine eigene "Zeitfenster ist
vorgerückt"-Logik braucht — der Filter wird ohnehin bei jedem Zyklus frisch
gegen das aktuelle `now` ausgewertet.

### Erweiterte Änderungserkennung über Start/Ende hinaus

Ein Quelltermin gilt als "geändert" gegenüber seinem letzten bekannten
Zustand, wenn `start`, `end`, `allDay`, `busy` **oder** `cancelled` vom
jeweiligen `lastKnown*`-Feld abweicht — nicht mehr nur `start`/`end`. Es
gibt fachlich keinen Grund, zwischen diesen fünf Feldern zu unterscheiden,
da sie gemeinsam den für Outlook relevanten Zustand des Blockers
beschreiben (Zeitfenster plus die Attribute, die eine erneute Anfrage
rechtfertigen). `recurring` bleibt bewusst **kein** Vergleichsfeld — ob ein
Vorkommen aus einer Serie stammt, ist reine Herkunftsinformation ohne
Auswirkung auf den gerenderten Blocker.

Eine wichtige Konsequenz: Ein Termin, der nachträglich z. B. auf
"nicht beschäftigt" umgestellt wird, löst dadurch ein Update aus, obwohl er
laut Erstellungs-Filter gar nicht mehr erstellungsberechtigt wäre. Das ist
kein Widerspruch zur obigen Gate-Invariante — der Filter wird weiterhin
ausschließlich für die Neuanlage befragt, die erweiterte Änderungserkennung
ist ein komplett separater Mechanismus im bereits bestehenden
Aktualisierungs-Zweig und lässt den Absage-Zweig unangetastet.

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
