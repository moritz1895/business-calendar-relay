# ADR-001: Eingebettetes, dateibasiertes H2 als StateStore, eine Adapter-Instanz pro konfiguriertem Quellkalender

**Datum:** 2026-07-28
**Status:** Angenommen

## Kontext

`StateStore` muss die Relay-Zuordnung (Quell-`UID` → Blocker-`UID`/
`SEQUENCE`) über Neustarts hinweg zuverlässig überstehen — ein
In-Memory-Speicher würde bei jedem Neustart alle bisher erstellten Blocker
als "neu" erscheinen lassen und Duplikate in Outlook erzeugen. Gleichzeitig
soll der Service ohne externe Infrastruktur betreibbar bleiben: Es gibt
keinen bestehenden Datenbankserver, den dieses Projekt voraussetzen könnte,
und der Betriebsaufwand soll für einen Service dieser Größe minimal
bleiben. Zusätzlich unterstützt die Konfiguration beliebig viele
Quellkalender (`relay.calendars`), jeder mit eigener, unabhängiger
Relay-Historie.

## Entscheidung

`JpaStateStoreAdapter` implementiert `StateStore` über Spring Data JPA
gegen eine **eingebettete H2-Datenbank im Dateimodus** (Pfad konfigurierbar
über `STATE_STORE_DATA_DIR`), nicht im In-Memory-Modus. Es existiert **eine
Adapter-Instanz pro konfiguriertem Quellkalender**, jede mit dem jeweiligen
`id`-Wert aus `relay.calendars` als `sourceCalendarId` parametrisiert; der
zusammengesetzte Geschäftsschlüssel je Zeile ist
`(sourceCalendarId, sourceUid)`. Alle Instanzen teilen sich dieselbe
zugrunde liegende H2-Datenbank und denselben `RelayStateJpaRepository`, da
jede Instanz durch `sourceCalendarId` bereits strikt auf ihre eigenen
Zeilen beschränkt ist.

Diese Adapter-Instanzen werden bewusst nicht als auto-gescannte,
parameterlose Spring-Singletons registriert (siehe ADR-006), sondern von
`RelayWiringConfiguration` explizit per `new` gebaut, da ihr Konstruktor
eine zur Laufzeit aus der Konfiguration bekannte `sourceCalendarId`
benötigt, für die kein generischer Spring-Bean existieren kann.

## Konsequenzen

- Der Service startet und läuft vollständig ohne externe Datenbank oder
  sonstige Infrastruktur — ein einzelnes gemountetes Datenverzeichnis
  genügt (in Docker als Volume).
- Der Zustand überlebt Prozessneustarts, solange `STATE_STORE_DATA_DIR` auf
  einem persistenten Datenträger liegt; wird dieses Verzeichnis verloren
  (z. B. Container ohne Volume neu erstellt), verliert die Anwendung die
  gesamte Relay-Historie und behandelt beim nächsten Poll alle Termine
  fälschlich als neu.
- Eine eingebettete Datei-Datenbank skaliert nicht auf mehrere gleichzeitig
  laufende Anwendungsinstanzen (kein Cluster-Betrieb ohne Weiteres möglich)
  — für die aktuelle Betriebsgröße (ein Prozess, einige Quellkalender) ist
  das eine akzeptable Einschränkung, keine für einen verteilten Betrieb
  ausgelegte Lösung.
- Da `id` aus `relay.calendars` der persistente Schlüssel ist, darf dieses
  Feld nach dem ersten Relay-Lauf eines Kalenders nie umbenannt werden — ein
  bewusst dokumentierter Betriebsvertrag (siehe README), keine technische
  Absicherung im Code.
