# ADR-004: Programmatischer `TaskScheduler` statt `@Scheduled`

**Datum:** 2026-07-28
**Status:** Angenommen

## Kontext

Jeder konfigurierte Quellkalender braucht einen eigenen, wiederkehrenden
Poll-Zyklus im gleichen Intervall (`relay.poll-interval`). Spring bietet
dafür üblicherweise `@Scheduled`-annotierte Methoden an — das
naheliegende, deklarative Standardmuster. `@Scheduled` setzt jedoch eine zur
Kompilierzeit feststehende, feste Anzahl von Methoden voraus. Die Anzahl
der tatsächlich zu planenden Zyklen ist hier aber erst zur Laufzeit aus
`relay.calendars` bekannt — je nach Konfiguration null, ein oder beliebig
viele Quellkalender, jeder mit einer eigenen
`PollAndRelaySourceCalendarUseCase`-Instanz.

## Entscheidung

`PollAndRelaySchedulerAdapter` verzichtet auf `@Scheduled` und plant
stattdessen programmatisch: Er erhält die vollständige Liste der zur
Laufzeit gebauten `PollAndRelaySourceCalendarUseCase`-Instanzen sowie einen
injizierten `TaskScheduler` und ruft für jede Instanz
`taskScheduler.scheduleWithFixedDelay(...)` einzeln auf. Die Planung
startet erst auf `ApplicationReadyEvent`, nicht während der
Bean-Konstruktion oder in `@PostConstruct`, damit der erste Poll-Zyklus
grundsätzlich erst läuft, wenn der gesamte Anwendungskontext — einschließlich
aller indirekt benötigten Beans — vollständig hochgefahren ist.

## Konsequenzen

- Die Anzahl der geplanten Zyklen folgt automatisch der zur Laufzeit
  konfigurierten Kalenderliste, ohne dass Code angepasst werden muss, wenn
  ein Kalender hinzukommt oder wegfällt.
- Die Scheduling-Logik ist expliziter und etwas länger als eine einzelne
  `@Scheduled`-Methode, dafür aber an einer einzigen Stelle
  (`PollAndRelaySchedulerAdapter`) nachvollziehbar, statt implizit über
  Spring-Infrastruktur verteilt.
- Da die Planung erst nach `ApplicationReadyEvent` beginnt, verzögert sich
  der erste Poll-Zyklus jedes Kalenders geringfügig gegenüber einem
  theoretisch früheren `@Scheduled`-Start — ein bewusst akzeptierter
  Trade-off zugunsten eines garantiert vollständig initialisierten
  Kontexts.
- `TaskScheduler`s Poolgröße wird in `RelayWiringConfiguration` explizit
  auf mindestens die Anzahl konfigurierter Kalender gesetzt, damit alle
  Zyklen parallel statt gegenseitig blockierend laufen können — das ist
  eine direkte Folge der programmatischen statt deklarativen Planung.
