# GraalVM Native-Image Build

Der produktive `Dockerfile`-Build kompiliert die Anwendung zu einem GraalVM
native-image-Binary statt zu einem JVM-Jar. Grund ist der Container-Footprint:
gemessen per `docker stats` liegt der native-image-Container idle bei
~75-85MB RSS gegenüber ~400-450MB auf der JVM (gleiche Anwendung, gleicher
Funktionsumfang — Spring Boot, Hibernate/JPA, eingebettetes H2, kein REST-Surface
außer Actuator-`/health`). Der Container startet in ~0.3-0.7s
(`Started BusinessCalendarRelayApplication in ...`), da kein JIT-Warmup und
keine Klassenverifikation zur Laufzeit mehr stattfinden.

## Mehrstufiger `Dockerfile`-Build

Zwei Stages:

1. **Builder** (`ghcr.io/graalvm/native-image-community:25`): enthält bereits
   GraalVM-JDK, den native-image-Compiler und den für den Linker-Schritt
   nötigen `gcc`/`cc`-Toolchain. Maven wird per `curl`+`tar` obendrauf
   installiert (kein Paketmanager nötig, umgeht Oracle-Linux-Repo-/
   Subscription-Fragen). `pom.xml`/`settings.xml` werden vor `src/` kopiert,
   damit `mvn dependency:go-offline` als eigener, cachebarer Layer läuft;
   erst danach `COPY src ./src` und `mvn -Pnative ... native:compile`.
2. **Runtime** (`ubuntu:24.04`): kopiert ausschließlich das fertige,
   dynamisch gelinkte Binary (`target/business-calendar-relay`) aus dem
   Builder. `ghcr.io/graalvm/native-image-community:25` basiert auf Oracle
   Linux 10.1 (glibc 2.39); `ubuntu:24.04` bringt dieselbe glibc-Major/Minor-
   Version mit, sodass das dynamisch gelinkte Binary unverändert läuft.
   Läuft als fester, nicht-root User `uid:gid 10001` (`relay`), analog zum
   vorherigen JVM-Setup.

Der Maven-Aufruf im Builder-Stage braucht dieselben `SMTP_*`/`RELAY_*`-
Dummy-Werte wie ein echter Start: `spring-boot-maven-plugin`s `process-aot`-
Goal (automatisch Teil des `native`-Profils) bootet den echten Spring-Context,
um AOT-Artefakte zu erzeugen, und verlangt dafür dieselbe
Pflichtkonfiguration ohne Default wie ein Produktivlauf — auch wenn während
des Builds nirgends eine echte Verbindung aufgebaut wird.

## Was `mvn -Pnative native:compile` tut

Das `native`-Maven-Profil (`pom.xml`, um Zeile 250) verkettet drei Schritte:

1. **`spring-boot-maven-plugin:process-aot`** — bootet den Spring-Context
   einmal zur Build-Zeit und erzeugt generierte Bean-Definitionen/Reflection-
   Hints statt sie erst zur Laufzeit per Classpath-Scanning aufzubauen.
   Kommt automatisch mit, sobald das `native`-Profil aktiv ist — keine
   eigene Plugin-Deklaration in `pom.xml` nötig, Version/Bindung stammen aus
   `spring-boot-starter-parent`.
2. **`native-maven-plugin` (`compile-no-fork`)** — ruft den eigentlichen
   `native-image`-Compiler auf und verlinkt das Ergebnis zu einem
   Standalone-Binary. `add-reachability-metadata` (implizit Teil dieses
   Plugins) zieht dabei automatisch community-gepflegte
   Reachability-Metadata für `hibernate-core`, `h2`, `HikariCP`,
   `hibernate-validator`, `tomcat-embed-core`, `jaxb-runtime` und
   `commons-logging` aus dem GraalVM Reachability Metadata Repository.
3. **`hibernate-enhance-maven-plugin`** (`org.hibernate.orm.tooling`,
   Version `7.0.0.Beta1` — bewusst nicht `${hibernate.version}`, siehe
   Kommentar in `pom.xml`: der Plugin-Artefakt hinkt `hibernate-core`s
   Release-Kadenz hinterher) — Build-Time-Bytecode-Enhancement der
   `@Entity`-Klassen (`enableLazyInitialization`, `enableDirtyTracking`,
   `enableAssociationManagement`). Ersetzt Hibernates sonst zur Laufzeit per
   ByteBuddy generierte `HibernateProxy`-Subklassen durch Logik, die direkt
   zur Build-Zeit in die `.class`-Dateien der Entities gewoben wird — siehe
   unten, warum das für native-image überhaupt nötig ist.

Alle drei sind an das `native`-Profil gebunden, der Standard-JVM-Build
(`mvn package`, kein Profil) bleibt davon unberührt und weiterhin über
`mvn spring-boot:run` nutzbar.

## Das Hibernate-Bytecode-Provider-Problem

GraalVM native-image erzwingt eine Closed-World-Annahme: Klassen können zur
Laufzeit nicht mehr definiert werden, nur was zur Build-Zeit analysiert wurde,
existiert im fertigen Binary. Hibernate 6+ hat Javassist entfernt und
konstruiert seinen `BytecodeProvider` bedingungslos per `ServiceLoader` — das
Ergebnis ist immer eine ByteBuddy-gestützte Implementierung, die sowohl
`HibernateProxy`-Subklassen (pro `@Entity`, für Lazy-Loading) als auch
schnelle Reflection-Accessor-Klassen (`getReflectionOptimizer()`, eine reine
Performance-Optimierung gegenüber normaler Reflection) **zur Laufzeit**
generiert. Beides bricht unter native-image mit
`UnsupportedFeatureError: Classes cannot be defined at runtime`.

`hibernate.bytecode.provider=none` — der klassische Fix für ältere
Hibernate/Javassist-Setups — greift hier nicht: In Hibernate 7.2 existiert
in `AvailableSettings` keine `reflection`/`optimizer`-Einstellung mehr, die
den ByteBuddy-Provider abschalten könnte.

### Warum Spring Frameworks eigener Fix nicht funktioniert

`spring-orm` bringt selbst eine `META-INF/native-image/org.springframework/
spring-orm/native-image.properties` mit, die genau dieses Problem adressieren
soll: `Args = -H:ServiceLoaderFeatureExcludeServices=
org.hibernate.bytecode.spi.BytecodeProvider`. Dieses Flag ist **nicht** die
in diesem Projekt verwendete Lösung — es filtert nachweislich nur
Modulpfad-`ServiceLoader`-Registrierungen (`jdk.internal.module.
ServicesCatalog`/`ModuleDescriptor.Provides`). `hibernate-core` wird hier
aber als klassenpfad-basiertes Automatic Module aufgelöst, und dessen
`META-INF/services/org.hibernate.bytecode.spi.BytecodeProvider`-Eintrag wird
über klassenpfad-basiertes Resource-Scanning gefunden
(`LazyClassPathLookupIterator`), das dieses Flag nicht erfasst. Empirisch
bestätigt: das Flag taucht im tatsächlich ausgeführten `native-image`-
Build-Kommando sichtbar auf, das fertige Binary scheitert zur Laufzeit aber
trotzdem mit dem ByteBuddy-gestützten Provider. Der `ServiceContributor`-
Ansatz unten umgeht `ServiceLoader`-Discovery vollständig, statt zu
versuchen, sie zu unterdrücken.

### Der tatsächliche Fix: `NoneBytecodeProviderServiceContributor`

`adapters/outbound/persistence/NoneBytecodeProviderServiceContributor.java`
implementiert Hibernates `org.hibernate.service.spi.ServiceContributor`-SPI
und reicht Hibernate dessen eigenen, mitgelieferten No-Op-`BytecodeProvider`
(`org.hibernate.bytecode.internal.none.BytecodeProviderImpl`) direkt an
`StandardServiceRegistryBuilder` durch — statt über `ServiceLoader`-Discovery
aufgelöst zu werden, wie es Hibernate sonst bedingungslos tut:

```java
@Override
public void contribute(StandardServiceRegistryBuilder serviceRegistryBuilder) {
    serviceRegistryBuilder.addService(BytecodeProvider.class, new BytecodeProviderImpl());
}
```

Registriert wird der Contributor über die Standard-Java-`ServiceLoader`-
Mechanik für `ServiceContributor` selbst (`META-INF/services/
org.hibernate.service.spi.ServiceContributor`, in `module-info.java`
gespiegelt über `provides org.hibernate.service.spi.ServiceContributor with
...`) — Hibernate ruft jeden gefundenen `ServiceContributor` früh im
Bootstrap auf und lässt ihn Services direkt in die Registry eintragen, bevor
Hibernates eigene `ServiceLoader`-basierte `BytecodeProvider`-Suche greifen
würde. Der zuletzt registrierte Service gewinnt, sodass der No-Op-Provider
den ByteBuddy-Provider verdrängt, ohne dessen Discovery-Pfad je anzustoßen.

`BytecodeProviderImpl` liegt in Hibernates `.internal.none`-Paket, nicht in
dessen `.spi`-Vertrag — ein künftiges Hibernate-Upgrade könnte die Klasse
ohne Deprecation-Zyklus umbenennen oder entfernen. Ein eigener Unit-Test auf
`NoneBytecodeProviderServiceContributor` sowie der volle
Context-Startup-Test der Suite fangen das direkt beim nächsten Build ab,
statt erst beim ersten produktiven JPA-Bootstrap.

Der No-Op-Provider baut grundsätzlich **keine** Lazy-Proxies — weder für
Assoziationen noch für einzelne Felder. Das ist für diese Anwendung
folgenlos, weil ihre sechs Entities ohnehin keine
`@ManyToOne`/`@OneToOne`/`@OneToMany`/`@ManyToMany`-Assoziationen besitzen;
sowohl die ByteBuddy-Proxy-Factories als auch dessen Reflection-Optimizer
waren hier reiner, unbedingt bei jedem `SessionFactory`-Bootstrap anfallender
Overhead, den das Domänenmodell nie gebraucht hat.

## Invariante: keine Lazy-Assoziationen

`NoLazyJpaAssociationsTest`
(`src/test/java/ms/rohde/businesscalendarrelay/adapters/outbound/persistence/NoLazyJpaAssociationsTest.java`)
sichert die Annahme ab, auf der der obige Fix beruht: er iteriert über alle
sechs `@Entity`-Klassen (`CalendarReplicaResourceEntity`,
`CalendarSyncTokenEntity`, `GoogleCalendarReplicaResourceEntity`,
`GoogleCalendarSyncTokenEntity`, `PendingCreationEntity`, `RelayStateEntity`)
und schlägt fehl, sobald eines ihrer Felder eine
`@OneToMany`/`@ManyToOne`/`@OneToOne`/`@ManyToMany`-Annotation trägt oder ein
`@Basic`-Feld explizit `FetchType.LAZY` setzt. Der No-Op-`BytecodeProvider`
verweigert das Bauen von Lazy-Proxies vollständig, statt sie funktionslos zu
ignorieren — eine künftig hinzugefügte Lazy-Assoziation würde sonst erst
beim Hibernate-Bootstrap zur Laufzeit scheitern. Dieser Test macht es
stattdessen sofort sichtbar, sobald ein neues Feld die Invariante verletzt.

## Lokal iterieren: `Dockerfile.graalvm-spike`

`Dockerfile.graalvm-spike` (Repo-Root, nicht Teil des produktiven Builds) ist
dasselbe GraalVM-JDK-Image mit obendrauf installiertem Maven wie die
Builder-Stage des echten `Dockerfile`, aber ohne `COPY`-Schritte — stattdessen
werden Repo und lokaler `.m2`-Cache per Bind-Mount eingebunden, sodass
Rebuilds den Docker-Layer-Cache umgehen und stattdessen Mavens eigenen
Inkrementalitäts-/Dependency-Cache direkt vom Host nutzen. Für schnelle
Iteration beim Debuggen von native-image-Problemen, nicht als Ersatz für den
echten `Dockerfile`-Build:

```bash
docker build -f Dockerfile.graalvm-spike -t bcr-graalvm-spike .
docker run --rm --entrypoint mvn \
  -v "$(pwd)":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace \
  -e SMTP_HOST=localhost -e SMTP_USERNAME=x -e SMTP_PASSWORD=x \
  -e RELAY_ORGANIZER_EMAIL=o@example.com -e RELAY_ATTENDEE_EMAIL=b@example.com \
  -e RELAY_FROM_ADDRESS=r@example.com -e RELAY_REPLY_TO_ADDRESS=o@example.com \
  bcr-graalvm-spike -Pnative -s settings.xml -DskipTests native:compile
```

(`--entrypoint mvn` ist nötig, da das Basisimage selbst `native-image` als
Entrypoint mitbringt. Unter Git Bash auf Windows zusätzlich
`MSYS_NO_PATHCONV=1` voranstellen, damit Bash die Pfade nicht selbst
umschreibt — auf einem echten Linux-Docker-Host irrelevant.)

## Zusammenspiel mit `build-and-export-image.sh`

`build-and-export-image.sh` baut das Image immer mit `--no-cache`
(`docker compose build --no-cache`) und exportiert es per `docker save` als
Tarball, für ein Zielsystem ohne Zugriff auf das interne Maven-Repository
oder die Build-Toolchain. Da das Image jetzt über native-image gebaut wird,
dauert dieser Schritt spürbar länger als ein reiner Jar-Build (native-image
selbst braucht mehrere Minuten allein für die Analyse-/Compile-Phase) — das
Skript weist per Kommentar darauf hin. Am Ablauf selbst (Build, Export,
`docker load` auf dem Zielsystem, Start über `docker-compose.deploy.yml`)
ändert sich nichts.
