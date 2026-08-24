# Spike: GraalVM Native Image Feasibility

Status: **in progress, paused** -- not a finished result, not a recommendation to ship.
Started 2026-08-24 out of the container-footprint investigation (PR #54/#55): the JVM
flags/limits from #54 cap the footprint at ~512m but don't get this service under
~150-250MB; GraalVM native-image is the only lever researched that plausibly gets a
Spring Boot + Hibernate + JPA app like this one under 100MB (comparable real-world
setups report 60-150MB RSS vs. 400-450MB on the JVM).

## What this spike proved

- **Tooling works without installing anything on the dev machine's OS.** The GraalVM
  JDK + native-image compiler (incl. the `gcc`/`cc` C toolchain needed for linking) all
  live inside `ghcr.io/graalvm/native-image-community:25`. `Dockerfile.graalvm-spike`
  (repo root, not part of the real build) layers Maven on top of that image via a plain
  `curl`+`tar` install (no package manager needed, avoids Oracle Linux repo/subscription
  issues). Run anything with:

  ```bash
  docker build -f Dockerfile.graalvm-spike -t bcr-graalvm-spike .
  MSYS_NO_PATHCONV=1 docker run --rm --entrypoint mvn \
    -v "$(pwd)":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace \
    -e SMTP_HOST=localhost -e SMTP_USERNAME=x -e SMTP_PASSWORD=x \
    -e RELAY_ORGANIZER_EMAIL=o@example.com -e RELAY_ATTENDEE_EMAIL=b@example.com \
    -e RELAY_FROM_ADDRESS=r@example.com -e RELAY_REPLY_TO_ADDRESS=o@example.com \
    bcr-graalvm-spike -Pnative -s settings.xml -DskipTests native:compile
  ```

  (`--entrypoint mvn` is required -- the base image's own entrypoint is `native-image`.
  `MSYS_NO_PATHCONV=1` is a Git-Bash-on-Windows-only requirement, irrelevant on the real
  Linux Docker host. The `SMTP_HOST`/`RELAY_*` env vars are dummy values only needed
  because `spring-boot-maven-plugin:process-aot` actually boots the Spring context, which
  needs the same required-with-no-default config as a real run.)

- **The project's dynamic per-calendar wiring (`RelayWiringConfiguration`,
  `PerCalendarComponentBeanDefinitionPruner`) is not a native-image concern.** Initial
  worry was that building a runtime-sized `List<PollAndRelaySourceCalendarUseCase>` from
  `relay.calendars` conflicts with native-image's closed-world assumption. It doesn't:
  no Spring bean definitions are dynamically registered per calendar (the whole list is
  built with plain `new` calls inside one `@Bean` method), and the pruner removes a
  fixed, config-independent set of 8 class names deterministically every time. Spring
  AOT processing captures this correctly.

- **Community reachability metadata covers the framework dependencies automatically.**
  `native-maven-plugin`'s `add-reachability-metadata` goal pulled in working
  configuration for `hibernate-core:7.2.12.Final`, `h2:2.4.240`, `HikariCP:7.0.2`,
  `hibernate-validator:9.0.1.Final`, `tomcat-embed-core:11.0.21`, `jaxb-runtime:4.0.6`,
  and `commons-logging:1.3.6` without any manual configuration -- exactly as the
  GraalVM Reachability Metadata Repository research (see the chat this spike came from)
  predicted.

- **ical4j 4.1.1's own bundled config is real but incomplete for our usage.** The jar
  ships `META-INF/native-image/com.fortuna.ical4j/{reflect,resource}-config.json` (note:
  under the legacy `com.fortuna.ical4j` path despite the jar's actual Maven coordinates
  being `org.mnode.ical4j:ical4j` -- harmless, native-image doesn't care about the
  directory naming, only that files exist under some `META-INF/native-image/**` path) and
  registers itself as a `java.time.zone.ZoneRulesProvider` via
  `META-INF/services/java.time.zone.ZoneRulesProvider`. Running the existing test suite
  (265 tests, no real calendar credentials needed) under the GraalVM tracing agent
  (`-agentlib:native-image-agent=config-merge-dir=...`, wired as the `native-agent-trace`
  Maven profile) auto-discovered ~150 additional ical4j classes (every `VEVENT`
  property/parameter/component type the app's own reference-data-driven tests exercise)
  into `src/main/resources/META-INF/native-image/reachability-metadata.json` --
  confirming ical4j's reflection footprint for what this app actually uses is small and
  bounded, not sprawling.

  One caveat: the tracing agent run has to exclude `JpaStateStoreAdapterTest`
  (`-Dtest=!JpaStateStoreAdapterTest` or similar) -- attaching
  `-agentlib:native-image-agent` alongside Mockito's own inline-mock-maker agent
  corrupts ByteBuddy mock generation for 3 of that class's tests (`UnfinishedStubbing`/
  `AbstractMethodError` on a mocked `RelayStateJpaRepository`). This is a tooling
  conflict between the two Java agents, not an application bug -- the other 262 tests
  ran and traced fine in the same process.

## What's still open (where this spike paused)

Two real, well-understood-but-unresolved `--initialize-at-build-time`/
`--initialize-at-run-time` class-initialization conflicts, both fixed via
`src/main/resources/META-INF/native-image/ms.rohde/business-calendar-relay-*-fix/native-image.properties`
(deliberately *not* named after the real artifactId -- `spring-boot-maven-plugin:process-aot`
generates its own `native-image.properties` at that exact conventional path and silently
shadows anything placed there by hand):

1. **`net.fortuna.ical4j.model.{DefaultZoneRulesProvider,TimeZoneLoader}`** --
   **resolved**: `--initialize-at-build-time` for both (ical4j's own timezone data is
   static/deterministic, safe to bake in; this was GraalVM's own suggested fix, and it's
   the same mechanism as the `ZoneRulesProvider` SPI registration -- see the detailed
   heap-reachability trace in the build log if revisiting this, it shows the object was
   reached via `ZoneRulesProvider.getProvider` through an entirely unrelated
   `org.springframework.web.util.pattern.PathPatternParser` static initializer that
   happens to format a `ZonedDateTime` for an exception message).

2. **Cascading `org.apache.logging.log4j`/`org.apache.logging.slf4j` object graph --
   unresolved.** Something touched during `process-aot`'s context refresh (plausibly
   `PerCalendarComponentBeanDefinitionPruner`'s `LOG.debug(...)` call inside
   `postProcessBeanDefinitionRegistry`, which genuinely runs during AOT processing)
   causes a live `Logger` object graph to get embedded in the image heap. Marking classes
   build-time-safe one at a time revealed a real cascade: `Log4jLogger` ->
   `org.apache.logging.log4j.core.Logger` -> `MarkerManager$Log4jMarker` -> (branches to)
   `Logger$PrivateConfig` / `Log4jMarkerFactory` / `ParameterizedMessageFactory` ->
   `org.springframework.boot.logging.log4j2.SpringBootPropertySource` ->
   `org.springframework.boot.ansi.AnsiColor`. Widening to whole-package directives
   (`org.apache.logging.slf4j`, `org.apache.logging.log4j`,
   `org.springframework.boot.logging`, `org.springframework.boot.ansi`, all
   build-time-init) got furthest, but the **last build run saw `Log4jLogger` reappear as
   fatal even though it's covered by the `org.apache.logging.slf4j` package directive
   already in the same file** -- not yet root-caused; possibly a directive-ordering or
   merge-precedence issue between this file and Spring's own AOT-generated
   `native-image.properties`/reachability-metadata at the reserved
   `ms.rohde/business-calendar-relay` path, possibly something reachability-order-dependent
   in the points-to analysis itself (analysis reached fewer types on that run than the
   one before -- 11,885 vs. a higher count previously -- suggesting non-determinism
   worth re-checking before concluding it's a real regression).

log4j-core 2.25.4 itself ships its own bundled reachability metadata (per Apache's own
GraalVM docs), but **`log4j-slf4j2-impl` (the SLF4J bridge module we actually use) does
not** -- confirmed by inspecting the jar directly, no `META-INF/native-image/**` inside
it at all. That gap is real and is presumably why this cascade exists at all; a cleaner
fix than blanket-marking whole packages build-time-safe might be filing/checking for an
upstream fix in `log4j-slf4j2-impl`, or reconsidering whether `LOG.debug(...)` calls
inside `BeanDefinitionRegistryPostProcessor`s (which run during both normal startup *and*
AOT processing) are worth guarding differently.

## Next steps, if resumed

1. Re-run the last build (`native-build14.log` locally, not committed --
   `target/` is gitignored) to see if the `Log4jLogger` regression reproduces
   consistently or was a one-off non-deterministic analysis artifact.
2. If it reproduces: try `-H:+PrintClassInitialization` (GraalVM diagnostic flag) to get
   the exact reachability trace for why `Log4jLogger` is still flagged despite the
   package directive, rather than continuing to whack-a-mole individual classes.
3. Once `native:compile` succeeds, the real proof is still missing: does the resulting
   binary actually **start** (Spring context refresh can behave differently at
   native-image runtime than during AOT processing) and **pass a smoke poll cycle**
   against a real or Mailpit-backed calendar? Measure actual RSS via `docker stats`
   against a container built from the native binary at that point, not before.
4. `src/main/resources/META-INF/native-image/reachability-metadata.json` (the
   tracing-agent output at the top level, not namespaced) is raw/uncurated -- it
   contains a lot of Spring Boot's own generic optional-dependency probing (Jackson
   XML/YAML/CBOR, Guava, Gson, ICU, QueryDSL, Rome feeds, logback -- none of which this
   app actually uses) picked up incidentally during the tracing run. Worth pruning to
   just the ical4j-relevant entries before this spike's output is considered
   presentable, rather than shipping it as-is.
5. Decide whether `--initialize-at-build-time` for the log4j/slf4j/Spring-Boot-logging
   packages is actually semantically safe long-term (baking in a `LoggerContext` at
   build time is usually fine for a simple Console-appender-only setup like this one's
   `log4j2.xml`, but should be verified against real log output once the binary runs,
   not just assumed from the build succeeding).
