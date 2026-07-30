# Scheduler & Multi-Kalender-Verdrahtung

Dieses Dokument beschreibt, wie `relay.calendars` zur Laufzeit in tatsächlich
laufende Poll-Zyklen übersetzt wird: die programmatische Schedulierung über
`PollAndRelaySchedulerAdapter` und die Bean-Verdrahtung in
`config/RelayWiringConfiguration.java`, die dafür pro konfiguriertem
Kalender eine eigene Use-Case-Instanz baut.

## Scheduler: `PollAndRelaySchedulerAdapter`

`PollAndRelaySchedulerAdapter`
(`adapters/inbound/scheduling/PollAndRelaySchedulerAdapter.java`, ein
`@DrivingAdapter`) ist der einzige Akteur, der
`PollAndRelaySourceCalendarUseCase.pollAndRelay()` aufruft.

### Warum kein `@Scheduled`

Die Anzahl der Use-Case-Instanzen steht erst zur Laufzeit fest — sie ergibt
sich aus der Größe von `relay.calendars` (potenziell `0..n` Einträge, siehe
`RelayProperties`). Ein festes Set von `@Scheduled`-annotierten Methoden
passt strukturell nicht auf eine zur Laufzeit variable Anzahl unabhängiger
Zyklen. Stattdessen injiziert der Adapter die volle Liste
`List<PollAndRelaySourceCalendarUseCase>` sowie einen `TaskScheduler` und
plant programmatisch:

```java
@EventListener(ApplicationReadyEvent.class)
public void schedulePollCycles() {
    for (var useCase : useCases) {
        taskScheduler.scheduleWithFixedDelay(() -> runPollCycle(useCase), pollInterval);
    }
    LOG.info("Scheduled {} source calendar(s) for polling every {}", useCases.size(), pollInterval);
}
```

Jeder konfigurierte Kalender bekommt damit seinen eigenen, unabhängigen
`scheduleWithFixedDelay`-Zyklus mit fester Verzögerung
(`relay.poll-interval`, ein `Duration`-Wert, geteilt von allen Kalendern).

### Warum `ApplicationReadyEvent`, nicht `@PostConstruct`

Die Schedulierung startet auf `ApplicationReadyEvent`, nicht während der
Bean-Konstruktion oder in `@PostConstruct`. Dadurch läuft der allererste
Poll-Zyklus garantiert erst, nachdem der gesamte Anwendungskontext —
inklusive aller Beans, von denen die Use-Cases indirekt abhängen könnten —
vollständig hochgefahren ist.

### `TaskScheduler`-Bean

`RelayWiringConfiguration#relayTaskScheduler`:

```java
@Bean(destroyMethod = "shutdown")
TaskScheduler relayTaskScheduler(RelayProperties relayProperties) {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(Math.max(MINIMUM_TASK_SCHEDULER_POOL_SIZE, relayProperties.calendars().size()));
    scheduler.setThreadNamePrefix("relay-poll-");
    scheduler.initialize();
    return scheduler;
}
```

Ein dedizierter `ThreadPoolTaskScheduler` (nicht der von Spring
möglicherweise sonst bereitgestellte Default-Scheduler) mit Poolgröße
`max(1, Anzahl konfigurierter Kalender)` — jeder Kalender bekommt effektiv
einen eigenen Thread, damit ein langsamer oder hängender Poll-Zyklus eines
Kalenders die anderen nicht verzögert. Thread-Namen tragen das Präfix
`relay-poll-`. `destroyMethod = "shutdown"` sorgt für sauberes Herunterfahren
des Pools beim Anwendungsstopp.

### Logging pro Zyklus

`PollAndRelaySchedulerAdapter` ist die einzige Stelle mit Sichtbarkeit auf
das Ergebnis jedes Zyklus (`RelayCycleResult`, `core/app/RelayCycleResult.java`)
und loggt entsprechend:

- **INFO** bei einem sauberen Durchlauf (kein `failed`-Eintrag):
  `"Poll cycle completed: created={}, updated={}, cancelled={}"`
- **WARN**, sobald mindestens ein Versand fehlgeschlagen ist, zusätzlich mit
  den betroffenen `sourceUid`s:
  `"Poll cycle completed with failures: created={}, updated={}, cancelled={}, failedSourceUids={}"`

Eine zur Laufzeit unerwartet aus `pollAndRelay()` geworfene
`RuntimeException` (statt eines regulären `RelayCycleResult` mit
`failed`-Einträgen) bricht nur den einen Zyklus dieses Kalenders ab und wird
separat als WARN geloggt (`"Poll cycle aborted unexpectedly"`) — der nächste
`scheduleWithFixedDelay`-Durchlauf läuft trotzdem wieder an. Die Logger-
Konfiguration (`src/main/resources/log4j2.xml`) setzt das Anwendungspaket
`ms.rohde.businesscalendarrelay` auf Level `INFO`; alles außerhalb (z. B.
Spring-interne Logger) bleibt auf `WARN` (Root-Logger).

## Multi-Kalender-Verdrahtung

`RelayWiringConfiguration` (`config/RelayWiringConfiguration.java`) baut pro
Eintrag in `RelayProperties#calendars()` eine vollständige
Use-Case-Instanz: einen eigenen `CalDavCalendarSourceAdapter` und einen
eigenen `JpaStateStoreAdapter`, verdrahtet zu einem
`PollAndRelaySourceCalendarService`. Gemeinsam genutzt (nicht pro Kalender
neu erzeugt) werden ein `HttpClient`-Bean, ein `Clock`-Bean
(`Clock.systemUTC()`), das eine `BlockerSink`-Bean (der SMTP-Adapter, der
keinen Kalender-spezifischen Zustand hat) und das eine
`RelayStateJpaRepository`.

```java
@Bean
List<PollAndRelaySourceCalendarUseCase> pollAndRelaySourceCalendarUseCases(
        RelayProperties relayProperties,
        HttpClient relayCalDavHttpClient,
        Clock relayClock,
        BlockerSink blockerSink,
        RelayStateJpaRepository relayStateJpaRepository) {
    return buildUseCases(relayProperties, relayCalDavHttpClient, relayClock, blockerSink, relayStateJpaRepository);
}
```

Die eigentliche Zuordnung (`buildUseCases`/`buildUseCase`) ist bewusst eine
`static` Methode, unabhängig vom Spring-Kontext testbar (reine Funktion
Konfiguration → Use-Case-Liste), ohne dass dafür ein Spring-Context gebootet
werden muss.

### Das Eager-Singleton-Problem

`@ArchComponentScan` (deklariert auf `BusinessCalendarRelayApplication`) ist
im Kern ein `@ComponentScan`, dessen Include-Filter jede Klasse erfasst, die
mit `@ArchComponent` meta-annotiert ist — das trifft sowohl auf
`@ApplicationService` als auch auf `@InfrastructureServiceAdapter` zu. Dieser
Scan registriert für jede so annotierte Klasse eine Bean-Definition rein
aufgrund der Annotation, unabhängig davon, ob die Klasse tatsächlich einen
Spring-auflösbaren (no-arg oder komplett Bean-referenzierbaren) Konstruktor
hat.

Fünf Klassen sind davon betroffen. Vier, weil ihre Konstruktoren
Kalender-spezifische `String`/`URI`-Werte statt Spring-Bean-Referenzen
erwarten:

- `PollAndRelaySourceCalendarService` (`core/app`)
- `JpaStateStoreAdapter` (`adapters/outbound/persistence`)
- `JpaPendingCreationQueueAdapter` (`adapters/outbound/persistence`, seit
  Issue #16, `docs/features/burst-filter-initialization.md`)
- `CalDavCalendarSourceAdapter` (`adapters/outbound/caldav`)

Die fünfte, `InMemoryBurstBudgetAdapter` (`adapters/outbound/throttling`,
ebenfalls seit Issue #16), ist **nicht** pro Kalender, sondern eine einzige,
geteilte Instanz — trifft aber dieselbe Falle aus einem anderen Grund: ihr
Konstruktor erwartet `int`/`Duration`-Konfigurationswerte aus
`RelayProperties.initialization()`, für die es ebenfalls keinen passenden
Spring-Bean gibt; `RelayWiringConfiguration`s `relayBurstBudget`-`@Bean`-
Factory-Methode baut die eine tatsächlich verwendete Instanz bereits von
Hand.

Unbehandelt würde Spring versuchen, alle fünf Klassen während des
Context-Refresh als parameterlose Singletons zu instanziieren, und mit einer
`UnsatisfiedDependencyException` scheitern, da kein passender Bean
existiert, um die jeweiligen Konstruktoren zu befriedigen.

### Die Lösung: `PerCalendarComponentBeanDefinitionPruner`

`config/PerCalendarComponentBeanDefinitionPruner.java` ist ein
`BeanDefinitionRegistryPostProcessor`, der die fünf betroffenen,
auto-gescannten Bean-Definitionen (per exaktem Klassennamen-Abgleich)
entfernt, **bevor** irgendeine Singleton-Vorinstanziierung stattfinden kann:

```java
@Override
public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
    for (var beanName : registry.getBeanDefinitionNames()) {
        var beanClassName = registry.getBeanDefinition(beanName).getBeanClassName();
        if (beanClassName != null && PER_CALENDAR_COMPONENT_CLASS_NAMES.contains(beanClassName)) {
            registry.removeBeanDefinition(beanName);
        }
    }
}
```

Registriert wird dieser Postprocessor über eine eigene, bewusst winzige
`@Configuration`-Klasse, `config/PerCalendarComponentPruningConfiguration.java`,
deren `@Bean`-Factory-Methode `static` ist:

```java
@Bean
static BeanDefinitionRegistryPostProcessor perCalendarComponentBeanDefinitionPruner() {
    return new PerCalendarComponentBeanDefinitionPruner();
}
```

Die `static`-Methode ist hier kein Stilelement, sondern funktional
notwendig: `BeanDefinitionRegistryPostProcessor`-Beans müssen laut Spring so
früh registriert werden, dass eine nicht-statische `@Bean`-Methode die
frühzeitige Instanziierung der gesamten sie deklarierenden
`@Configuration`-Klasse erzwingen würde. Mit einer eigenen, ansonsten leeren
Konfigurationsklasse bleibt `RelayWiringConfiguration` davon unberührt.

Die Entscheidung, die Bean-Definitionen zu **entfernen** statt z. B. die drei
Klassen mit `@Lazy` zu versehen, ist bewusst: Nichts im Anwendungskontext
fragt diese Typen je über den Spring-Typ-Mechanismus an — sie werden
ausschließlich manuell über `new` in `RelayWiringConfiguration` konstruiert.
Es soll also erst gar keine Bean-Definition für sie existieren. Das hält
zusätzlich alle vier betroffenen Klassen frei von jeder
`org.springframework.*`-Annotation — für
`PollAndRelaySourceCalendarService` (in `core/app`) ist das keine
Stiloption, sondern von `CLAUDE.md` verbindlich vorgeschrieben.
