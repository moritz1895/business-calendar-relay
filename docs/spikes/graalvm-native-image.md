# Spike: GraalVM Native Image Feasibility (historisches Rechercheprotokoll)

Diese Spike ist abgeschlossen und in den produktiven Build übernommen worden.
Die aktuelle, gültige Dokumentation des native-image-Builds steht in
[`docs/technical/native-image-build.md`](../technical/native-image-build.md)
— dieses Dokument ist **keine Pflichtlektüre** mehr und beschreibt keinen
aktuellen Zustand. Es bleibt ausschließlich als Rechercheprotokoll erhalten,
weil eine der hier gefundenen Einschränkungen über den gelösten Fall hinaus
weiterhin gilt (siehe unten).

## Einzige noch relevante Erkenntnis: `ClassPredefinitionFeature` und Java-25-Bytecode

GraalVM CE 25.0.2s internes `ClassPredefinitionFeature` (der
"Predefined Classes"-Mechanismus, den `native-image-agent=...,
experimental-class-define-support` erzeugt) bringt seinen eigenen,
separaten ASM-basierten Klassenleser mit — unabhängig von ByteBuddys
eigenem ASM-Leser, für den `-Dnet.bytebuddy.experimental=true` die passende
Abhilfe ist. GraalVMs interner Leser unterstützt Java-25-Bytecode (Class-File
Major Version 69) nicht: ein Versuch, mit dieser Methode aufgezeichnete
Predefined Classes in `native:compile` einzuspeisen, scheitert nach langer
Analysephase mit `IllegalArgumentException: Unsupported class file major
version 69` in
`com.oracle.svm.hosted.ClassPredefinitionFeature$PredefinedClassesRegistryImpl.add`.
Es gibt dafür keine bekannte Escape-Hatch analog zu
`net.bytebuddy.experimental`.

Für das in diesem Projekt tatsächlich aufgetretene Problem (Hibernates
ByteBuddy-Bytecode-Provider) war dieser Mechanismus ohnehin nicht die
gewählte Lösung — siehe `native-image-build.md` für den tatsächlichen Fix
(`NoneBytecodeProviderServiceContributor`). Relevant bleibt diese Erkenntnis
nur, falls ein künftiges, anderes native-image-Problem in diesem Projekt
erneut über `experimental-class-define-support`/Predefined Classes gelöst
werden müsste: der Java-25-Bytecode dieses Projekts (`CLAUDE.md`, nicht
verhandelbar) macht diesen Weg mit der aktuell verfügbaren GraalVM-Version
unbrauchbar, solange GraalVMs eigener ASM-Leser nicht nachzieht.

## Was diese Spike sonst noch geklärt hat

Kurz zusammengefasst, ohne Sitzungsprotokoll — Details, falls je wieder
benötigt, in der Git-Historie dieser Datei:

- Das dynamische Per-Kalender-Wiring (`RelayWiringConfiguration`,
  `PerCalendarComponentBeanDefinitionPruner`) ist kein native-image-Problem:
  keine Bean-Definitionen werden dynamisch registriert, Spring AOT erfasst
  die feste Pruning-Logik korrekt.
- Community-Reachability-Metadata deckt die Kern-Frameworks
  (`hibernate-core`, `h2`, `HikariCP`, `hibernate-validator`,
  `tomcat-embed-core`, `jaxb-runtime`, `commons-logging`) automatisch ab.
- ical4js eigene, mitgelieferte Reachability-Metadata reicht für den
  tatsächlich genutzten Property-/Komponenten-Umfang aus.
- Mehrere Klasseninitialisierungs-Kaskaden (Log4j2/SLF4J-Bridge,
  Commons-Logging-Bridge, ical4j-Zeitzonencache) ließen sich über gezielte
  `--initialize-at-build-time`-Direktiven auflösen.

Diese Punkte sind vollständig erledigt und im produktiven Build (`pom.xml`,
`Dockerfile`, `src/main/resources/META-INF/native-image/`) verankert; sie
werden hier nicht weiter im Detail nachgezeichnet.
