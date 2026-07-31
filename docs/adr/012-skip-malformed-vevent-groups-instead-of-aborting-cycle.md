# ADR-012: Semantisch unvollständige VEVENT-Gruppen überspringen statt den ganzen Poll-Zyklus abzubrechen

**Datum:** 2026-07-31
**Status:** Angenommen

## Kontext

`CalDavCalendarSourceAdapter.expandAll(...)` gruppiert alle geparsten
`VEVENT`-Komponenten nach `UID` und expandiert jede Gruppe einzeln zu
`SourceEvent`(s) (`expandSeries(...)`). Bis zu diesem ADR ließ jede dieser
Operationen — fehlende `UID`, fehlendes `DTSTART`/`DTEND`, oder ein
`RECURRENCE-ID`-Override ohne zugehörigen Master — eine
`CalDavCalendarSourceException` bis in `readEvents()` durchschlagen und
damit den **gesamten** Poll-Zyklus für den betroffenen Kalender scheitern,
unabhängig davon, wie viele andere, vollständig valide `VEVENT`s in
derselben Antwort enthalten waren. Das war ursprünglich bewusst so
dokumentiert (siehe `CalDavCalendarSourceException`s vorherige Javadoc):
konsistent mit dem Alles-oder-nichts-Verhalten bei Netzwerkfehlern,
unerwarteten HTTP-Status oder gänzlich unparsbarer `calendar-data`.

Gegen einen echten, seit Jahren gewachsenen Nextcloud-Kalender zeigte sich,
dass dieses Verhalten in der Praxis zu grob ist: ein einzelner, vermutlich
verwaister `VEVENT`-Eintrag ganz ohne `DTSTART` blockierte den kompletten
Poll-Zyklus für diesen Kalender dauerhaft — jeder Zyklus scheiterte an
genau demselben Eintrag erneut, ohne dass auch nur ein einziger der
übrigen, validen Termine je verarbeitet wurde. Anders als bei einer
komplett unparsbaren Antwort (siehe `parseVEvents(String)`, weiterhin
Alles-oder-nichts) ist hier die Ausgangslage anders: die Antwort wurde
erfolgreich als ICS geparst, nur eine einzelne `VEVENT`-Gruppe darin ist
semantisch unvollständig — die übrigen Gruppen sind weiterhin
vertrauenswürdige Daten.

## Entscheidung

`expandAll(...)` fängt `CalDavCalendarSourceException` jetzt an zwei
Stellen pro `VEVENT`/UID-Gruppe ab, statt sie propagieren zu lassen:

- beim Ermitteln der `UID` selbst (`requireUid(...)`),
- beim Expandieren einer UID-Gruppe (`expandSeries(...)`, deckt fehlendes
  `DTSTART`/`DTEND` und Override-ohne-Master ab).

In beiden Fällen wird die betroffene Gruppe übersprungen und die
Exception-Nachricht bei `WARN` geloggt (inklusive `UID`, sofern bekannt);
der Rest der `VEVENT`s wird unverändert weiterverarbeitet.

Ausdrücklich **nicht** von dieser Änderung betroffen sind Fehler, die vor
der Gruppierung auftreten: ein nicht-`207`-Status, gänzlich unparsbares
Multistatus-XML, oder ein `calendar-data`-Blob, der nicht einmal als ICS
geparst werden kann (`parseVEvents(String)`). Für diese gilt weiterhin das
bisherige Alles-oder-nichts-Verhalten, da hier keinerlei Teil der Antwort
als vertrauenswürdig gelten kann.

## Konsequenzen

- Ein einzelner strukturell kaputter Kalendereintrag (fehlendes
  `DTSTART`/`DTEND`/`UID`, verwaister Override) blockiert nicht mehr
  dauerhaft die Synchronisation des gesamten Kalenders — die übrigen,
  validen Termine werden weiterhin korrekt als Blocker gespiegelt.
- Der defekte Eintrag selbst bleibt unbemerkt in Outlook, bis er im
  Quellkalender repariert oder gelöscht wird — es gibt keine aktive
  Benachrichtigung außer dem `WARN`-Log-Eintrag bei jedem Poll-Zyklus, in
  dem der Eintrag noch vorhanden ist.
- `CalDavCalendarSourceException` wird jetzt für zwei unterschiedliche
  Schweregrade verwendet (siehe aktualisierte Javadoc der Klasse selbst):
  vollständiger Abbruch bei nicht vertrauenswürdiger Antwort insgesamt,
  versus überspringbar bei einer einzelnen semantisch unvollständigen
  `VEVENT`-Gruppe innerhalb einer ansonsten validen Antwort. Wer den
  Exception-Typ an anderer Stelle fängt, muss diese Unterscheidung anhand
  des Aufrufkontexts treffen, nicht anhand des Typs allein.
