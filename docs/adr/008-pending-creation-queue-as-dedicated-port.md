# ADR-008: Eigener `PendingCreationQueue`-Port statt Erweiterung von `StateStore`

**Datum:** 2026-07-30
**Status:** Angenommen

## Kontext

Wird ein Quellkalender zum ersten Mal angebunden (oder ein weiterer
Kalender später hinzugefügt), liefert der bestehende Erstellungs-Filter
(ADR-007) zwar keine vergangenen Termine mehr, aber jeden zukünftigen
Einzeltermin und jedes Vorkommen einer wiederkehrenden Serie innerhalb des
Wiederholungs-Zeitfensters im selben Poll-Zyklus als Erstellung. Bei einer
gewachsenen Kalenderhistorie können das hunderte iMIP-Mails in Sekunden
sein — das Risiko, dass der Mailserver des Business-Postfachs den Absender
als Spam-Quelle einstuft oder sperrt (GitHub-Issue #16). Die vollständige
Erstanlage-Liste, die `RelayDiffPlanner.plan(...)` beim allerersten
Poll-Zyklus eines Kalenders (leerer `StateStore`) ohnehin in einem Rutsch
berechnet, muss deshalb einmalig eingesammelt, persistiert und über
mehrere Poll-Zyklen hinweg scheibchenweise abgearbeitet werden, statt
sofort komplett versendet zu werden.

Naheliegend wäre gewesen, `StateStore` bzw. `RelayState`/`relay_state` um
einen dritten Lebenszyklus-Zustand ("noch nicht einmal erstellt, wartet
nur auf seinen Sendezeitpunkt") zu ergänzen, statt einen neuen Port
einzuführen.

## Entscheidung

`StateStore`s Methodensignaturen (`loadAll()`, `save(...)`,
`markCancelled(...)`) bleiben vollständig unverändert. Die
Rückstands-Warteschlange bekommt stattdessen einen eigenen, dedizierten
Outbound-Port `PendingCreationQueue` (`loadAllOrderedByStart()`,
`saveAll(...)`, `remove(...)`) mit eigener Tabelle (`pending_creation`),
konfiguriert eine Instanz pro Quellkalender, exakt wie `StateStore` selbst.

Begründung: `RelayState`s Invarianten (`lastKnownStart`/`lastKnownEnd`
nicht null, `active` bedeutet "existiert und nicht storniert") passen
fachlich nicht zu "existiert noch gar nicht als Blocker, wartet nur auf
seinen Sendezeitpunkt" — das wäre eine Verwässerung eines bereits klar
geschnittenen Wertobjekts für einen fachlich andersartigen Zustand. Ein
eigener Port mit eigener Tabelle hält beide Konzepte sauber getrennt und
lässt `StateStore` sowie jede bestehende `StateStore`-Implementierung
unangetastet. Ein Warteschlangen-Eintrag ist zudem strukturell nichts
anderes als ein bereits vorhandenes `RelayAction.Create` (`sourceUid`,
`blockerUid`, `sequence` immer `0`, `start`, `end`, `allDay`, `busy`,
`cancelled`) — es wird bewusst kein eigener `PendingCreate`-Domänentyp
eingeführt, um keine zweite, parallele Sende-Pipeline neben dem
bestehenden `processCreate` zu erzeugen.

## Konsequenzen

- `StateStore` und jede bestehende `StateStore`-Implementierung
  (`JpaStateStoreAdapter`) bleiben vollständig unangetastet — kein
  Migrationsrisiko für bereits laufende Kalender-Instanzen.
- Zwei separate, aber strukturell fast identische Tabellen (`relay_state`,
  `pending_creation`) mit überlappenden Spalten (`source_calendar_id`,
  `source_uid`, `blocker_uid`, Zeitfenster) existieren nebeneinander —
  bewusst in Kauf genommene Redundanz zugunsten sauber getrennter
  fachlicher Zustände, statt eines einzigen, aber überladenen
  Zustandsmodells.
- Der Initialisierungs-Zustand eines Kalenders ("noch nie initialisiert"
  vs. "initialisiert") ist vollständig aus den beiden bereits vorhandenen
  Quellen ableitbar (`pendingQueue.isEmpty() && priorStates.isEmpty()`) —
  es braucht kein zusätzliches, persistiertes "initialisiert"-Flag und
  keinen dritten Tabellenzustand.
- `JpaPendingCreationQueueAdapter` folgt strukturell exakt dem Aufbau von
  `JpaStateStoreAdapter` (Konstruktor mit Repository + `sourceCalendarId`,
  kein auto-gescannter Spring-Singleton-Bean, `PerCalendarComponentBeanDefinitionPruner`
  entfernt dieselbe Art überflüssiger Bean-Definition wie in ADR-006
  beschrieben) — der bestehende Pro-Kalender-Wiring-Mechanismus musste für
  diese Feature nicht erweitert werden, nur ein weiteres Mal angewendet.
