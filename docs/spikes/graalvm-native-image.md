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

## Class-initialization cascade -- resolved, `native:compile` now succeeds (2026-08-25)

The `Log4jLogger` regression from the previous session **did reproduce** on the first
re-run, with a more precise root cause than originally suspected: not primarily
`PerCalendarComponentBeanDefinitionPruner`'s `LOG.debug(...)`, but the completely
ordinary `private static final Logger LOG = LogManager.getLogger(...)` field pattern
used in every adapter class (e.g. `SmtpBlockerSinkAdapter`) -- any one of them is enough
to pull in the whole `LogManager`/`Log4jContextFactory`/`ClassLoaderContextSelector`
object graph.

The whole-package directives from the previous session (`org.apache.logging.slf4j`,
`org.apache.logging.log4j`, `org.springframework.boot.logging`,
`org.springframework.boot.ansi`, all `--initialize-at-build-time`) turned out to be
**necessary but not sufficient** -- `-H:+PrintClassInitialization`'s own CSV report
confirmed `org.apache.logging.slf4j` was correctly registered as `BUILD_TIME`, yet
`Log4jLogger` (a class directly inside that package) was still fatally flagged as
run-time-default during the heap scan. Adding the **exact class name** on top of the
package directive (`org.apache.logging.slf4j.Log4jLogger`) fixed it -- so package-level
coverage and per-class coverage are evidently not fully equivalent in this GraalVM
version for this scenario; empirically add the exact class if the package directive
alone doesn't hold, rather than assuming the CSV report guarantees enforcement.

From there it was a clean, deterministic whack-a-mole (each fix's rebuild revealed
exactly the next class in the same object graph, no more non-determinism observed
across five consecutive builds):

1. `org.apache.logging.slf4j.Log4jLogger` (exact class, see above) -- fixed.
2. `net.fortuna.ical4j.util.MapTimeZoneCache` -- fixed by widening the ical4j directive
   from two named classes to the whole `net.fortuna.ical4j` package (safe: these are
   plain POJOs/static timezone data, no runtime-only state in their static initializers).
3. `net.fortuna.ical4j.model.component.Standard` and `.TimeZoneUpdater` -- both cleared
   by the same package widening in step 2.
4. `org.apache.commons.logging.impl.Log4jApiLogFactory$Log4j2Log` -- a second,
   independent logger-bridge object (commons-logging's own log4j2 bridge, most likely
   pulled in via Hibernate, which logs through commons-logging rather than slf4j) got
   flagged once the log4j chain itself was clear. Fixed by adding
   `org.apache.commons.logging` to the same build-time package list.

Final working `native-image.properties` for the two `-fix` directories:
`business-calendar-relay-ical4j-fix` -> `--initialize-at-build-time=net.fortuna.ical4j`;
`business-calendar-relay-log4j-fix` ->
`--initialize-at-build-time=org.apache.logging.slf4j,org.apache.logging.log4j,org.springframework.boot.logging,org.springframework.boot.ansi,org.apache.logging.slf4j.Log4jLogger,org.apache.commons.logging`
(plus `-H:+PrintClassInitialization`, harmless to leave in, writes a diagnostic CSV to
`target/reports/` on every build).

`native:compile` now completes successfully end-to-end (`BUILD SUCCESS`, ~3 minutes for
the native-image step alone on this machine, 191MB binary at
`target/business-calendar-relay`, includes bundled AWT/JDK shared libraries that are
part of the GraalVM JDK image and not necessarily indicative of final container size).

## What's still open (where this spike paused, 2026-08-25)

**The binary starts** (Spring Boot banner prints, context refresh begins -- real proof
that Spring AOT's generated bean definitions work at native-image runtime, not just at
build time) but **fails during JPA/Hibernate bootstrap**, in two sequential issues:

1. **Missing reflection registration for `BytecodeProviderImpl`'s no-arg constructor**
   -- resolved. The bundled community reachability metadata pulled in is for
   `hibernate-core:7.2.0.Final` (see "Configuration directory not found, trying latest
   version" in the build log) while the project uses `7.2.12.Final`; that version gap is
   presumably why this one reflection entry is missing. Fixed by adding
   `src/main/resources/META-INF/native-image/ms.rohde/business-calendar-relay-hibernate-bytebuddy-fix/reachability-metadata.json`
   with a `"reflection"` entry for
   `org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl`'s `<init>()` --
   exactly the JSON snippet GraalVM's own error message suggested.

2. **Unresolved, and structurally different from every issue above**: once reflection
   can construct `BytecodeProviderImpl`, its constructor itself throws
   `com.oracle.svm.core.jdk.UnsupportedFeatureError: Classes cannot be defined at
   runtime by default when using ahead-of-time Native Image compilation. Tried to define
   class 'net.bytebuddy.utility.Invoker$Dispatcher'`. Hibernate's default bytecode
   provider (ByteByddy) generates and loads new classes at runtime
   (`ClassLoader.defineClass`) for entity enhancement/proxying -- fundamentally
   incompatible with native-image's closed-world assumption, not just a missing config
   entry. This is a well-known, documented Hibernate/GraalVM limitation, not a
   project-specific bug.

## Next steps, if resumed

1. **Try `hibernate.bytecode.provider=none` first** (a one-line JPA property, e.g.
   under `spring.jpa.properties.hibernate.bytecode.provider` in `application.yml`, ideally
   scoped to a `native` Spring profile rather than the default one). This disables
   ByteBuddy entirely and falls back to plain-reflection field/property access -- slower
   per-entity-access than enhanced bytecode, but very plausibly fine for this app's
   modest entity count and poll-cycle-driven (not high-throughput) access pattern. This
   is the standard, low-risk fix documented across the Spring-Boot-native-image
   ecosystem for exactly this failure mode. If it works, re-run the same start/stop
   smoke test used this session (see below) to confirm the app gets past JPA bootstrap.
2. If disabling the bytecode provider isn't acceptable (e.g. it turns out to matter for
   lazy-loading semantics actually exercised by this app), the fallback is Hibernate's
   `hibernate-enhance-maven-plugin` for **build-time** bytecode enhancement instead of
   ByteBuddy's runtime approach -- more setup, not yet investigated.
3. Once the app clears JPA bootstrap, re-run the smoke test and this time let it run
   long enough to complete a real poll cycle (`relay.poll-interval`, default 5m -- or
   override it lower via `RELAY_POLL_INTERVAL` just for this test) against a
   Mailpit-backed or dummy calendar, and measure actual RSS via `docker stats` --
   still nobody has measured a real number for this app's native-image footprint yet,
   which is the entire point of the spike.
4. `src/main/resources/META-INF/native-image/reachability-metadata.json` (the
   tracing-agent output at the top level, not namespaced) is still raw/uncurated -- see
   the previous version of this doc's equivalent note; still worth pruning to just the
   ical4j-relevant entries before this spike's output is considered presentable.
5. Decide whether `--initialize-at-build-time` for the log4j/slf4j/commons-logging/
   Spring-Boot-logging packages is semantically safe long-term -- now that the binary
   actually starts and logs (see the log excerpt in this session's build output), this
   can finally be checked against real log output instead of only assumed from the
   build succeeding.

**How to reproduce this session's state from a clean checkout of this branch:** the
Docker build/run commands under "What this spike proved" above are unchanged; only the
three `-fix` native-image config files under
`src/main/resources/META-INF/native-image/ms.rohde/` changed. To smoke-test the binary
after a successful `native:compile`, run it in a throwaway container built from the same
`bcr-graalvm-spike` image (guarantees compatible glibc) with the same dummy
`SMTP_*`/`RELAY_*` env vars as the compile step plus a scratch `STATE_STORE_DATA_DIR`,
then `docker logs`/`docker stats --no-stream` before stopping it -- `relay.calendars` is
empty by default so it starts with zero configured calendars, no real credentials
needed.
