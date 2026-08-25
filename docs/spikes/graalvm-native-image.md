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
    bcr-graalvm-spike -Pnative -s settings.xml -DskipTests -Dnet.bytebuddy.experimental=true native:compile
  ```

  (`--entrypoint mvn` is required -- the base image's own entrypoint is `native-image`.
  `MSYS_NO_PATHCONV=1` is a Git-Bash-on-Windows-only requirement, irrelevant on the real
  Linux Docker host. The `SMTP_HOST`/`RELAY_*` env vars are dummy values only needed
  because `spring-boot-maven-plugin:process-aot` actually boots the Spring context, which
  needs the same required-with-no-default config as a real run. `-Dnet.bytebuddy.experimental=true`
  is required since the "Hibernate/ByteBuddy runtime class generation" section below was
  written -- the `native` profile's `hibernate-enhance-maven-plugin` and bytebuddy's own
  build-time-init trick both need it to accept this project's Java 25 bytecode; omit it
  and the build fails with `Java 25 (69) is not supported by the current version of Byte
  Buddy`.)

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

## Hibernate/ByteBuddy runtime class generation -- narrowed to one specific, unresolved
gap; three sibling issues resolved (2026-08-25, same day, continued)

**The binary starts** (Spring Boot banner prints, context refresh begins -- real proof
that Spring AOT's generated bean definitions work at native-image runtime, not just at
build time) but **fails during JPA/Hibernate bootstrap**. Four issues in sequence, three
resolved:

1. **Missing reflection registration for `BytecodeProviderImpl`'s no-arg constructor**
   -- resolved. The bundled community reachability metadata pulled in is for
   `hibernate-core:7.2.0.Final` (see "Configuration directory not found, trying latest
   version" in the build log) while the project uses `7.2.12.Final`; that version gap is
   presumably why this one reflection entry is missing. Fixed by adding
   `src/main/resources/META-INF/native-image/ms.rohde/business-calendar-relay-hibernate-bytebuddy-fix/reachability-metadata.json`
   with a `"reflection"` entry for
   `org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl`'s `<init>()` --
   exactly the JSON snippet GraalVM's own error message suggested.

2. **`net.bytebuddy.utility.Invoker$Dispatcher` runtime class definition** -- resolved.
   Once reflection could construct `BytecodeProviderImpl`, its constructor threw
   `com.oracle.svm.core.jdk.UnsupportedFeatureError: Classes cannot be defined at
   runtime ... Tried to define class 'net.bytebuddy.utility.Invoker$Dispatcher'`.
   **`hibernate.bytecode.provider=none` does NOT fix this** -- tried first (it's the
   commonly-cited fix for older Hibernate/Javassist setups) and confirmed by direct
   source inspection of `hibernate-core:7.2.12.Final`'s `AvailableSettings.java`: no
   `reflection`/`optimizer`-related setting exists there anymore. Hibernate 6+ removed
   Javassist entirely; ByteBuddy is the only provider and gets constructed via
   `ServiceLoader` unconditionally regardless of this property. The actual fix: mark
   `net.bytebuddy` itself `--initialize-at-build-time` (added to the
   `business-calendar-relay-hibernate-bytebuddy-fix` directory's own
   `native-image.properties`, alongside its `reachability-metadata.json` from step 1).
   `JavaDispatcher.<clinit>` is a single, static, one-time class-generation trick
   bytebuddy uses internally to portably invoke JDK-internal reflective APIs across JDK
   versions -- forcing it to run during the native-image build (a real JVM, where
   runtime class definition is unrestricted) lets the resulting `Class` object be
   legitimately embedded as a build-time heap constant, which is exactly what GraalVM's
   own error message option 2 suggests doing.

   This surfaced a **separate, pre-existing issue**: the enhance-plugin (see #3) and
   bytebuddy's own tooling both bundle an ASM-based class reader that doesn't yet
   recognize Java 25 bytecode (class file major version 69, GraalVM CE 25.0.2 / ByteBuddy
   1.17.8) -- `Java 25 (69) is not supported by the current version of Byte Buddy which
   officially supports Java 23 (67)`. Bytebuddy's own error message names the fix:
   `-Dnet.bytebuddy.experimental=true` as a JVM system property on the `mvn` invocation
   (works because the enhance plugin executes in-process within Maven's own JVM, not
   forked -- the same flag is also needed for the `native:compile` run itself, since
   bytebuddy's `JavaDispatcher.<clinit>` runs during that build too). Both Docker
   commands under "What this spike proved" and "Bytecode enhancement" below need it.

3. **HibernateProxy runtime class generation** -- resolved via **build-time bytecode
   enhancement**, replacing Hibernate's default *runtime*-generated per-entity proxy
   subclasses entirely. Added `org.hibernate.orm.tooling:hibernate-enhance-maven-plugin`
   to the `native` Maven profile (`enableLazyInitialization`, `enableDirtyTracking`,
   `enableAssociationManagement` all `true`), bound to the same profile so the default
   JVM build is untouched. Two coordinate/version gotchas hit along the way, both now
   reflected in `pom.xml`'s comments:
   - **groupId is `org.hibernate.orm.tooling`**, not `org.hibernate.orm` (that groupId
     exists on Maven Central too, but only as a `pom`-packaging placeholder with no
     actual plugin jar -- resolves with a confusing "could not be resolved" error, not an
     obviously-wrong-groupId error).
   - **Version can't be `${hibernate.version}`** (7.2.12.Final, as managed by
     `spring-boot-starter-parent`) -- the plugin artifact's own release cadence lags
     hibernate-core's and tops out at `7.0.0.Beta1` on Maven Central as of this spike.
     Used `7.0.0.Beta1` directly; the enhancement bytecode format has historically stayed
     compatible across 7.x minors, and it worked without any observed issue.

   Full build command (env vars unchanged from before, `-Dnet.bytebuddy.experimental=true`
   newly required per #2):

   ```bash
   MSYS_NO_PATHCONV=1 docker run --rm --entrypoint mvn \
     -v "$(pwd)":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace \
     -e SMTP_HOST=localhost -e SMTP_USERNAME=x -e SMTP_PASSWORD=x \
     -e RELAY_ORGANIZER_EMAIL=o@example.com -e RELAY_ATTENDEE_EMAIL=b@example.com \
     -e RELAY_FROM_ADDRESS=r@example.com -e RELAY_REPLY_TO_ADDRESS=o@example.com \
     bcr-graalvm-spike -Pnative -s settings.xml -DskipTests -Dnet.bytebuddy.experimental=true native:compile
   ```

   With enhancement in place, the earlier `HibernateProxy` class-definition error is
   **completely gone** -- confirmed by reading `EntityRepresentationStrategyPojoStandard`
   (hibernate-core sources jar): `resolveProxyFactory` explicitly checks
   `entityPersister.getBytecodeEnhancementMetadata().isEnhancedForLazyLoading()` and
   skips building a `ProxyFactory` at all when true, exactly the condition enhancement
   satisfies. This app's 6 entities have no `@ManyToOne`/`@OneToOne`/`@OneToMany`
   associations at all, so this was pure unconditional Hibernate bootstrap overhead
   (every `@Entity` gets a proxy factory built regardless of whether anything's ever
   actually lazy-loaded), not something the app's domain model needed.

4. **Unresolved -- `getReflectionOptimizer()`'s fast-accessor class generation is
   unconditional in Hibernate 7.2, with no config-based way to disable it.** With the
   proxy issue gone, the *next* Hibernate bootstrap step fails the same way:
   `RelayStateEntity$HibernateInstantiator` (a bytebuddy-generated fast
   constructor/getter/setter class, purely a performance micro-optimization over plain
   reflection) can't be defined at native-image runtime either. Confirmed by reading
   `EntityRepresentationStrategyPojoStandard`'s constructor directly: unlike
   `resolveProxyFactory`, `resolveReflectionOptimizer(bytecodeProvider)` has **no
   enhancement-status check at all** -- it's called unconditionally for every entity,
   with no try/catch around it either. Read `AvailableSettings.java` directly (via the
   `-sources.jar`, already in the local `.m2` cache) and confirmed no
   `reflection`/`optimizer`-related setting exists in Hibernate 7.2 to skip this. Also
   confirmed in `BytecodeProviderImpl.getReflectionOptimizer`: even the one documented
   partial escape (a `private` no-arg constructor makes Hibernate skip the *instantiator*
   half, since "the current implementation of the ReflectionOptimizer contract can't call
   private constructors") does **not** help here -- a second, always-built
   `bulkAccessor` class (fast getters/setters) is generated unconditionally right after,
   regardless of constructor visibility.

   **GraalVM's own suggested last-resort workaround does not currently work either**:
   ran the plain (non-native) jar under
   `-agentlib:native-image-agent=config-merge-dir=...,experimental-class-define-support`
   (a real JVM, `java` from the same `bcr-graalvm-spike` GraalVM-JDK image -- `-Xshare`
   was not needed; did need to delete a stale `.lock` file in the config output
   directory after an earlier run was stopped mid-write) to capture the
   dynamically-generated classes ahead of time as "predefined classes". It worked as
   intended -- app started fully in ~56s (agent overhead is large) and captured 11,416
   classes (`agent-extracted-predefined-classes/` + `predefined-classes-config.json`),
   including the exact `RelayStateEntity$HibernateInstantiator`/`...__Accessor_*`
   classes needed, but scoped far wider than needed since the trace covers the *entire*
   app boot, not just Hibernate (the GraalVM error message's own caveat: "the resulting
   classes can contain entries from the classpath that should be manually filtered out").
   Feeding this into `native:compile` failed after a genuinely long analysis phase
   (~17 minutes) with a **GraalVM-internal crash**. `ClassPredefinitionFeature` bundles
   its *own*, separate ASM-based class reader (distinct from bytebuddy's, which is what
   `-Dnet.bytebuddy.experimental=true` in #2 fixed) -- that internal reader doesn't
   support Java 25 bytecode either, and there is no equivalent experimental-flag escape
   hatch discovered for GraalVM's own internal reader: `IllegalArgumentException:
   Unsupported class file major version 69` inside
   `com.oracle.svm.hosted.ClassPredefinitionFeature$PredefinedClassesRegistryImpl.add`.
   This is a genuine, environment-specific tooling gap (GraalVM CE 25.0.2's internal ASM
   vs. Java 25 target bytecode), not something fixable by better config -- reverted the
   generated predefined-classes artifacts and the reachability-metadata.json changes the
   agent run also merged in (both discarded, not committed; they don't help without a
   working predefined-classes path and the reachability-metadata.json bloat was ~3000
   lines of mostly-unrelated Spring Boot optional-dependency noise from a full real
   app-boot trace, worse than the existing top-level file's already-noted "raw/uncurated"
   problem).

## Next steps, if resumed

Everything below concerns exactly the one remaining blocker (#4 above); items 1-3 above
are done deals, no further action needed there.

1. **Try an older/different GraalVM native-image-community release** for the ASM
   major-version-69 support gap in `ClassPredefinitionFeature` -- this project targets
   Java 25 (`CLAUDE.md` requirement, not negotiable for this spike), so the fix has to
   come from GraalVM's side maturing, not from downgrading the app's bytecode target.
   Worth checking the GraalVM release notes/issue tracker for whether a newer point
   release already fixes this, since Java 25 is very recent as a native-image build
   target across the ecosystem.
2. Alternatively, **accept the reflection-optimizer's fast accessors as unsupportable
   for now and look for an upstream Hibernate fix instead** -- e.g. filing/checking
   whether `hibernate-core` has (or plans) a build-time-safe alternative to
   `getReflectionOptimizer()`'s bytebuddy dependency, comparable to what bytecode
   enhancement already did for the proxy-factory half of this same problem.
3. Once *this* is solved, still no real number has been measured for this app's
   native-image RSS footprint -- re-run the same start/stop smoke test used throughout
   this session, this time letting it run long enough to complete a real poll cycle
   (`relay.poll-interval`, override low via `RELAY_POLL_INTERVAL` for the test) against
   a Mailpit-backed or dummy calendar, and measure via `docker stats`. This remains the
   actual point of the whole spike and is still completely unmeasured.
4. `src/main/resources/META-INF/native-image/reachability-metadata.json` (the
   tracing-agent output at the top level, not namespaced) is still raw/uncurated from
   the *first* tracing-agent session (ical4j-focused, 265-test-suite run) -- still worth
   pruning to just the ical4j-relevant entries. Do **not** re-attempt the full-app-boot
   tracing-agent run to "refresh" this file -- see #4 above, that run's version of this
   file was reverted for being far noisier without being any more useful.
5. Decide whether `--initialize-at-build-time` for the log4j/slf4j/commons-logging/
   Spring-Boot-logging/`net.bytebuddy` packages is semantically safe long-term -- now
   that the binary actually starts and logs (see the log excerpts in this session's
   build/run output), this can finally be checked against real log output instead of
   only assumed from the build succeeding.

**How to reproduce this session's state from a clean checkout of this branch:** the
Docker build command is the one under "What this spike proved" above (now including
`-Dnet.bytebuddy.experimental=true`); `pom.xml`'s `native` profile gained the
`hibernate-enhance-maven-plugin`, and the `-fix` native-image config files under
`src/main/resources/META-INF/native-image/ms.rohde/` changed (`net.bytebuddy` added to
the hibernate-bytebuddy-fix directory's own `native-image.properties`, alongside its
existing `reachability-metadata.json`). `native:compile` succeeds end-to-end; the binary
still fails at the point described in "What's still open" #4 above, so there's currently
no fully-working binary to smoke-test further than that. To reproduce the failure
directly: run the resulting binary in a throwaway container built from the same
`bcr-graalvm-spike` image (guarantees compatible glibc) with the same dummy
`SMTP_*`/`RELAY_*` env vars as the compile step plus a scratch `STATE_STORE_DATA_DIR`,
then `docker logs` before stopping it -- `relay.calendars` is empty by default so it
gets as far as JPA bootstrap with zero configured calendars, no real credentials needed.
