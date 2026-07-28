# ADR-006: Entfernen automatisch gescannter Bean-Definitionen für pro-Kalender-Komponenten

**Datum:** 2026-07-28
**Status:** Angenommen

## Kontext

`@ArchComponentScan` (aus `hexagonal-arch-spring`) registriert automatisch
eine Spring-Bean-Definition für jede Klasse, die mit einem
`@ArchComponent`-Meta-Annotation (u. a. `@ApplicationService`,
`@InfrastructureServiceAdapter`) versehen ist — unabhängig davon, ob diese
Klasse tatsächlich über einen parameterlos auflösbaren Konstruktor verfügt.
Drei Klassen in diesem Projekt — `PollAndRelaySourceCalendarService`,
`JpaStateStoreAdapter` und `CalDavCalendarSourceAdapter` — tragen die
passenden Annotationen, sollen aber nie als generischer Spring-Bean nach
Typ aufgelöst werden: `RelayWiringConfiguration` konstruiert stattdessen
pro konfiguriertem Quellkalender eine eigene Instanz jeder Klasse per `new`,
da ihre Konstruktoren kalenderspezifische Konfigurationswerte
(`String`/`URI`) statt Spring-auflösbarer Abhängigkeiten benötigen. Ohne
Eingriff registriert der Scan sie trotzdem, und der Kontext-Start schlägt
mit einer `UnsatisfiedDependencyException` fehl, da Spring keine Bean vom
Typ `String`/`URI` zur Konstruktorauflösung finden kann.

## Entscheidung

`PerCalendarComponentBeanDefinitionPruner` (ein
`BeanDefinitionRegistryPostProcessor`) entfernt die automatisch registrierten
Bean-Definitionen dieser drei Klassen explizit, bevor Spring mit der
Vorinstanziierung von Singletons beginnt. Die Alternative — die drei
Klassen mit `@Lazy` zu markieren, um die Fehlschlag-beim-Start zu vermeiden
— wurde verworfen: Sie hätte die Klassen weiterhin als (zufällig nie
angeforderte) Spring-Beans bestehen lassen, obwohl inhaltlich kein Teil des
Anwendungskontexts sie je über ihren Typ anfordert. Das Entfernen der
Bean-Definition macht diese Absicht explizit, statt sie über einen
Lazy-Trick zu verschleiern, und hält insbesondere
`PollAndRelaySourceCalendarService` (in `core/app`) frei von jeder
`org.springframework.*`-Annotation — `CLAUDE.md` verbietet
Spring-Abhängigkeiten im Domänenkern und der Anwendungsschicht ohnehin.

## Konsequenzen

- Die drei betroffenen Klassen bleiben bewusst dumme, per `new`
  konstruierbare Objekte ohne jede Kenntnis von Spring — die
  Hexagonal-Architektur-Grenze bleibt sauber, auch wenn die Annotation
  formal auf sie zutrifft.
- Das Pruning ist ein zusätzlicher, nicht sofort offensichtlicher
  Konfigurationsschritt: Wer `@ArchComponentScan` und die
  hexagonal-arch-Annotationen kennt, aber `PerCalendarComponentBeanDefinitionPruner`
  nicht, könnte bei einer vierten pro-Kalender-Klasse denselben
  Kontext-Start-Fehler erneut erleben, bis sie ebenfalls in
  `PER_CALENDAR_COMPONENT_CLASS_NAMES` aufgenommen wird.
- Die Liste der zu entfernenden Klassennamen muss von Hand gepflegt
  werden, wenn künftig weitere pro-Kalender-parametrisierte Komponenten
  hinzukommen — es gibt keinen automatischen Erkennungsmechanismus dafür,
  der über die Namensliste hinausgeht.
