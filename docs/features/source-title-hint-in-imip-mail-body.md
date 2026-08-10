# Feature: Titel-Hinweis im Mailtext der iMIP-Einladung

Feature-Wunsch des Projekteigentümers: Die iMIP-Mails an das dienstliche
Postfach sollen zusätzlich den Titel des Original-Termins aus dem privaten
Quellkalender tragen — aber ausschließlich lesbar im Mailtext, niemals im
gerenderten Kalendereintrag selbst. Diese Spec erweitert
`docs/features/relay-orchestration.md` (Poll-and-Diff-Orchestrierung) und
schreibt gegen den aktuellen Code-Stand fort (Branch
`feat/source-title-hint-in-imip-mail-body`). Sie ändert nichts an der
Diff-Logik, den Erstellungs-/Änderungsregeln oder dem `SEQUENCE`-Handling
selbst.

## Feature-Zusammenfassung

`SourceEvent` trägt heute bewusst keinen Titel — das ist eine zentrale,
dokumentierte Invariante ("Blocker sind titellos by design", siehe
`docs/domain.md`). Diese Feature durchbricht diese Invariante **nicht**: Der
gerenderte Blocker im Geschäftskalender bleibt exakt so titellos wie heute
(`SUMMARY:Privater Blocker`, fixes Literal). Was sich ändert, ist einzig der
für den Empfänger sichtbare Mailtext der `METHOD:REQUEST`-Einladungsmail: Er
bekommt zusätzlich eine klar gekennzeichnete Hinweiszeile mit dem
ursprünglichen Titel des Quelltermins, damit der Betrachter des dienstlichen
Postfachs auf einen Blick einschätzen kann, ob ein Blocker inhaltlich
tatsächlich für den Dienstkalender relevant ist. Dazu muss `SourceEvent` neu
den Original-Titel (CalDAV `SUMMARY` bzw. Google `summary`) mitführen, dieser
muss bis in den iMIP-Mailversand durchgereicht werden, dort aber strikt vom
ICS-Text und vom `Subject`-Header ferngehalten werden.

> **Datenschutz-Kompromiss (bewusst, auf Wunsch des Auftraggebers).** Wer
> Zugriff auf das dienstliche Postfach hat — nicht nur der eigentliche
> Kalenderbetrachter — kann ab dieser Feature den Titel des privaten
> Originaltermins im Mailtext lesen, auch wenn er nie im Kalendereintrag
> selbst ankommt. Das steht im Spannungsverhältnis zum bisherigen
> "titellos by design"-Datenschutzprinzip aus `docs/domain.md` und ist hier
> bewusst dokumentiert, nicht übersehen: Es ist exakt der vom Auftraggeber
> gewünschte Zweck der Feature, keine unbeabsichtigte Nebenwirkung.

## Akteure

Unverändert gegenüber `relay-orchestration.md`: **Scheduler** ist der einzige
Akteur, der einen Poll-Zyklus auslöst. Menschlicher Akteur ist weiterhin nur
der Betrachter des dienstlichen Postfachs, der die resultierende Mail liest —
diese Feature macht diesen Betrachter erstmals zu einer fachlich relevanten
Zielgruppe für den Mailtext (bisher war der Mailtext reines MIME-Beiwerk ohne
fachlichen Inhalt).

## Use Cases

### Bestehender Use Case "Poll and Relay Source Calendar" — Erweiterung des Sende-Schritts

**Ziel:** unverändert — den Business-Kalender mit dem Quellkalender in Sync
halten. Neu daran beteiligt: die `REQUEST`-Mail transportiert zusätzlich
einen menschenlesbaren Titel-Hinweis.

**Vorbedingungen:** unverändert, ergänzt um: Der Quelltermin kann, muss aber
nicht, einen nicht-leeren Original-Titel besitzen (`SUMMARY`/`summary` im
Quellkalender).

**Hauptablauf (nur die geänderten Teilschritte, alles andere bleibt exakt
wie in `relay-orchestration.md`):**

1. `CalendarSource.readEvents()` liest wie bisher den vollständigen,
   aktuellen `SourceEvent`-Bestand — jedes `SourceEvent` trägt jetzt
   zusätzlich seinen Original-Titel, sofern im Quellkalender vorhanden.
2. `RelayDiffPlanner.plan(...)` trifft die Create-/Update-/Cancel-/
   No-op-Entscheidung exakt wie bisher — der Titel fließt in **keine** dieser
   Entscheidungen ein (siehe "Domänenmodell-Erweiterungen" unten).
3. Für eine `Create`- oder `Update`-Aktion: Der Original-Titel des
   auslösenden `SourceEvent` wird bis zum Mailversand durchgereicht.
   `ImipCalendarRenderer` rendert den ICS-Text unverändert titellos.
4. Beim Zusammenbauen der iMIP-Mail (`SmtpBlockerSinkAdapter`) wird der
   Mailtext (`multipart/alternative`: Plaintext + HTML) um eine zusätzliche,
   klar gekennzeichnete Hinweiszeile mit dem Original-Titel ergänzt — sofern
   ein Titel vorhanden ist (siehe Wortlaut-Vorschlag unten). Der
   `Subject`-Header und der ICS-Text bleiben davon vollständig unberührt.
5. Für eine `Cancel`-Aktion: keine Änderung. Kein Titel im Mailtext, kein
   Titel im Subject — siehe "Out of Scope".

**Fehlerfälle:** keine neuen. Ein fehlender oder leerer Original-Titel ist
kein Fehlerfall, sondern der Normalfall für einen titellosen Quelltermin —
der Mailtext lässt die Hinweiszeile in diesem Fall schlicht weg.

**Command-Form:** unverändert — weiterhin ausschließlich vom Scheduler
ausgelöst, keine neuen Eingabeparameter für den Use Case selbst.

**Result-Form:** unverändert — `RelayCycleResult` (created/updated/
cancelled/failed Listen von `sourceUid`s) bekommt kein neues Feld; der Titel
ist reine Mailtext-Nutzlast, kein Bestandteil der Zyklus-Bilanz.

## Domänenmodell-Erweiterungen

Kein neuer Aggregate Root, keine neue Entität — ausschließlich zusätzliche,
optionale Felder an drei bestehenden Wertobjekten sowie eine neue
Extraktionspflicht an den beiden Quellkalender-Adaptern.

| Wertobjekt | Neues Feld | Herkunft / Fluss |
|---|---|---|
| `SourceEvent` | `sourceTitle` (nullable, String) | Von `CalDavCalendarSourceAdapter` aus `SUMMARY` bzw. von `GoogleCalendarSourceAdapter` aus dem JSON-Feld `summary` gefüllt. `null`, wenn der Quelltermin keinen Titel trägt. |
| `RelayAction.Create` | `sourceTitle` (nullable, String) | Vom auslösenden `SourceEvent` übernommen, exakt nach demselben Muster wie die bereits vorhandenen `allDay`/`busy`/`cancelled`-Felder — damit die Anwendungsschicht den Titel bis zum Mailversand tragen kann, ohne den Quelltermin erneut zu lesen. |
| `RelayAction.Update` | `sourceTitle` (nullable, String) | Wie bei `Create`. |
| `BlockerEvent` | `sourceTitle` (nullable, String) | Von der Anwendungsschicht aus der auslösenden `RelayAction` übernommen. Wird an `ImipCalendarRenderer` durchgereicht, dort aber **nirgends gelesen** — reiner Transportbehälter bis zur Mail-Erzeugung. |

**Explizit NICHT geändert — muss so bleiben:**

- **`RelayAction.Cancel`** bekommt **kein** `sourceTitle`-Feld — konsistent
  damit, dass `Cancel` schon heute kein `allDay`/`busy`/`cancelled` trägt
  (siehe `docs/domain.md`: eine Absage braucht keinen `lastKnown*`-Stand
  mehr). Siehe "Out of Scope" für die Begründung.
- **`RelayState`** bekommt **kein** neues Feld (kein `lastKnownSourceTitle`),
  und damit auch keine Datenbank-Migration. Der Titel ist nicht Teil der
  persistierten Buchführung.
- **`RelayDiffPlanner.relayStateChanged(...)`** (die erweiterte
  Änderungserkennung über `start`/`end`/`allDay`/`busy`/`cancelled`) bleibt
  unverändert — `sourceTitle` fließt **nicht** in den Vergleich ein, exakt
  wie das bereits informationelle `recurring`-Feld heute. Praktische
  Konsequenz, die als bewusster Kompromiss zu dokumentieren ist: Ändert sich
  bei einem Quelltermin ausschließlich der Titel (Zeitfenster und Flags
  bleiben gleich), löst das keinen erneuten Versand aus — der Mailtext einer
  bereits gesendeten Einladung zeigt dann bis zum nächsten echten Create/
  Update-Trigger einen veralteten Titel. Akzeptiert, da der Hinweis ein
  Komfortfeature ist, kein Datenvertrag.
- **`ImipCalendarRenderer`** bleibt vollständig unverändert in seiner
  `SUMMARY`-Logik: `SUMMARY:Privater Blocker` bleibt das feste Literal, für
  `REQUEST` wie für `CANCEL`. Der Renderer bekommt zwar ein `BlockerEvent`
  mit einem gefüllten `sourceTitle`-Feld übergeben, darf es aber an keiner
  Stelle lesen oder in den ICS-Text einfließen lassen. Das ist die
  wichtigste Einzelinvariante dieser Feature und sollte durch einen
  expliziten Test abgesichert werden ("gerenderter ICS-Text enthält niemals
  den Original-Titel, unabhängig vom `sourceTitle`-Wert").
- **`SmtpBlockerSinkAdapter`**s `Subject`-Header-Literale (`TITLE =
  "Privater Blocker"`, `CANCELLED_SUBJECT = "Abgesagt: Privater Blocker"`)
  bleiben exakt unverändert. Hintergrund (siehe Javadoc dieser Klasse und
  Commit `f93a209`): Outlook rendert den `Subject`-Header der iMIP-Mail als
  Termintitel im Kalenderraster — nicht die ICS-`SUMMARY`. Würde der
  Original-Titel hier landen, wäre die titellose Anzeige im
  Geschäftskalender durchbrochen, exakt der bereits einmal aufgetretene und
  behobene Fehler. Der einzige sichere Ort für den Original-Titel bleibt der
  `multipart/alternative`-Mailtext, der als Geschwister-Teil neben dem
  `text/calendar`-Teil liegt.

## Port-Erweiterungen

Kein neuer Port, keine neue Portschnittstelle. Eine bestehende
Transport-DTO im `ports/outbound`-Paket bekommt ein neues Feld:

| Port-Typ | Änderung |
|---|---|
| `BlockerMail` (`ports/outbound`) | Neues Feld `sourceTitle` (nullable, String). Von der Anwendungsschicht beim Zusammenbauen der `BlockerMail` für eine `Create`/`Update`-Aktion aus `BlockerEvent.sourceTitle()` befüllt; für `Cancel`-Aktionen bleibt es `null`. `SmtpBlockerSinkAdapter` liest es beim Aufbau des `multipart/alternative`-Mailtexts. |
| `CalendarSource` (`ports/outbound`) | Schnittstelle selbst unverändert (`readEvents(): List<SourceEvent>`) — nur die von ihr zurückgegebenen `SourceEvent`-Instanzen tragen künftig das neue Feld. Beide implementierenden Adapter müssen es befüllen: |

- **`CalDavCalendarSourceAdapter`**: liest `Property.SUMMARY` — dieselbe
  ical4j-Property, die `describeForLogging(...)` heute bereits nur für
  Logzwecke ausliest — und übernimmt den Wert in jedes erzeugte
  `SourceEvent` (Einzeltermin über `toSingleSourceEvent`, jedes Vorkommen
  einer Serie über `expandRecurringSeries`, sowohl im Master- als auch im
  `RECURRENCE-ID`-Override-Zweig).
- **`GoogleCalendarSourceAdapter`**: liest das JSON-Feld `summary` aus dem
  von `events.list` zurückgegebenen Event-Objekt in `toSourceEvent(...)` und
  übernimmt es in jedes erzeugte `SourceEvent`.

Beide Adapter müssen einen fehlenden oder leeren `SUMMARY`/`summary`-Wert
sauber auf `null` abbilden (kein leerer String, kein Platzhaltertext) — ein
`SourceEvent` ohne Titel muss weiterhin genauso funktionieren wie heute, nur
eben ohne Hinweiszeile im Mailtext.

### Mailtext-Änderung (`SmtpBlockerSinkAdapter`)

Nur für `METHOD:REQUEST`-Mails (Create und Update — iTIP kennt hierfür keine
getrennte Methode) und nur, wenn `BlockerMail.sourceTitle()` nicht `null` ist,
wird sowohl der Plaintext- als auch der HTML-Teil um eine zusätzliche,
eindeutig als Hinweis gekennzeichnete Zeile ergänzt, getrennt von der
bestehenden Boilerplate-Formulierung. Wortlaut-Vorschlag (Deutsch, siehe
"Offene Fragen" — dieser Wortlaut ist ein Vorschlag, kein finaler Text):

**Plaintext-Teil (mit Titel):**

```
Diese Nachricht enthaelt eine Kalender-Einladung.

Ursprünglicher Titel im privaten Kalender: <Titel>
```

**Plaintext-Teil (ohne Titel — unverändert gegenüber heute):**

```
Diese Nachricht enthaelt eine Kalender-Einladung.
```

**HTML-Teil (mit Titel):**

```html
<html><body>
  <p>Diese Nachricht enthaelt eine Kalender-Einladung.</p>
  <p><strong>Ursprünglicher Titel im privaten Kalender:</strong> <Titel escaped></p>
</body></html>
```

Der Titel **muss** im HTML-Teil HTML-escaped werden (`&`, `<`, `>`, `"`, `'`),
bevor er in Markup eingebettet wird — er stammt aus einem privaten Kalender
und ist damit nicht vertrauenswürdige Nutzereingabe; ohne Escaping könnte ein
Titel wie `<script>...</script>` den HTML-Mailteil strukturell brechen oder
(sehr theoretisch, je nach Mailclient) ausführbaren Inhalt einschleusen. Der
Plaintext-Teil braucht kein Escaping.

## Out of Scope

- **`METHOD:CANCEL`-Mails.** Die Absage-Mail bekommt keinen Titel-Hinweis im
  Mailtext, und `RelayAction.Cancel` bekommt bewusst kein `sourceTitle`-Feld.
  Begründung: Für eine Absage existiert zum Zeitpunkt des Versands kein
  aktueller `SourceEvent` mehr (der Termin ist ja gerade aus dem
  Quellkalender verschwunden) — der einzige Weg, hier trotzdem einen Titel
  anzuzeigen, wäre, ihn zusätzlich in `RelayState` zu persistieren (neues
  Feld `lastKnownSourceTitle`, echte Datenbank-Migration). Das ist ein
  größerer, eigenständiger Schnitt als der hier angefragte und bewusst nicht
  Teil dieser Feature.
- **Persistierung des Titels in `RelayState`/der Datenbank.** Siehe oben —
  keine Migration, kein neues Feld, keine `lastKnown*`-Semantik für den
  Titel.
- **Titel als Vergleichsfeld für die Änderungserkennung.** Eine reine
  Titeländerung am Quelltermin (Zeitfenster/Flags unverändert) löst weiterhin
  keinen erneuten Versand aus, siehe "Domänenmodell-Erweiterungen" oben.
- **Jede Änderung am ICS-Text oder am `Subject`-Header.** Beide bleiben
  exakt wie heute titellos.
- **Titel-Bereinigung/Scrubbing/Kürzung.** Der Original-Titel wird
  unverändert (außer HTML-Escaping im HTML-Teil) durchgereicht; es gibt
  keine Längenbegrenzung, keine Filterung sensibler Inhalte im Titel selbst
  — das wäre ein eigenständiges Datenschutz-Feature, nicht Teil dieses
  Wunsches.

## Offene Fragen

1. **Exakter Wortlaut der Hinweiszeile ist nur ein Vorschlag.** Der oben
   vorgeschlagene Text ("Ursprünglicher Titel im privaten Kalender: …") ist
   nicht final abgestimmt und sollte vor der Umsetzung noch einmal vom
   Auftraggeber bestätigt werden — insbesondere, ob "im privaten Kalender"
   als Zusatz gewünscht ist oder ein kürzerer Text ("Titel: …") bevorzugt
   wird.
2. **Nur-Leerzeichen-Titel.** Zählt ein `SUMMARY`/`summary`-Wert, der nur
   aus Leerzeichen besteht, als "kein Titel" (→ `sourceTitle = null`, keine
   Hinweiszeile) oder als vorhandener, aber leerer Titel? Nicht entschieden
   — sollte vor der Umsetzung geklärt werden, da beide Adapter dieselbe
   Regel konsistent anwenden müssen.
3. **`RECURRENCE-ID`-Override ohne eigenes `SUMMARY`.** Trägt eine einzelne
   überschriebene Vorkommnis einer wiederkehrenden Serie (CalDAV
   `RECURRENCE-ID`-Override-Komponente) kein eigenes `SUMMARY`, ist unklar,
   ob `sourceTitle` für dieses Vorkommen auf den Titel der Serien-Master-
   Komponente zurückfallen oder `null` bleiben soll. Für Google Calendar
   stellt sich diese Frage nicht in gleicher Form, da `events.list` mit
   `singleEvents=true` bereits vollständig aufgelöste Einzelvorkommen mit
   jeweils eigenem `summary`-Feld liefert.
4. **Maximale Titellänge im Mailtext.** Es gibt aktuell keine Vorgabe, ob
   ein sehr langer Titel im Mailtext gekürzt werden soll. Da der Mailtext
   ohnehin nur menschliches Lesepublikum hat (nicht Teil des ICS mit seiner
   75-Oktett-Zeilenfaltung), ist dies vermutlich unkritisch, aber nicht
   explizit vom Auftraggeber adressiert worden.
