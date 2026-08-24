FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /app
COPY settings.xml pom.xml ./
RUN mvn -s settings.xml dependency:go-offline -q
COPY src ./src
RUN mvn -s settings.xml package -DskipTests -q

FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

# Fixed, explicit UID/GID (not Alpine's auto-assigned -S value, which isn't guaranteed
# stable across base-image versions) so a host bind-mount for /app/data (see
# docker-compose.yml) can be chowned to a predictable, documented owner on the host side.
RUN addgroup -g 10001 -S relay && adduser -u 10001 -S relay -G relay \
    && mkdir -p /app/data && chown -R relay:relay /app
USER relay

# Log timestamps (see log4j2.xml) are otherwise unanchored: this Alpine base ships no
# tzdata and defaults to UTC, which doesn't match the deployer's own wall clock when
# correlating a sporadic log entry against "when did I notice this". The JVM resolves TZ
# against its own bundled tzdb, independent of the OS tzdata package, so no extra apk
# install is needed. Overridable per deployment via the TZ environment variable.
ENV TZ=Europe/Berlin

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=12s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# Without an explicit heap/GC/thread budget, the JVM sizes itself against whatever the
# container *can see* rather than what it's actually limited to (see the mem_limit/cpus
# in docker-compose*.yml, which this depends on to mean anything): MaxRAMPercentage
# without a cgroup memory limit falls back to a fraction of the host's total RAM, and
# G1's default GC/JIT-compiler thread counts scale with the host's full core count. For
# a background poller with no real HTTP traffic (actuator health checks only -- see
# server.tomcat.threads.max in application.yml), that produced >1GB RSS and sustained
# 200% CPU on a 16-core host, observed directly. -XX:+UseSerialGC and
# -XX:TieredStopAtLevel=1 (skip the C2 JIT compiler) are the standard OpenJDK
# recommendation for exactly this profile -- low throughput, small heap, containerized --
# trading peak throughput this service never needs for materially less idle CPU and
# memory. Overridable per deployment via the JAVA_OPTS environment variable.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=25 -XX:+UseSerialGC \
-XX:TieredStopAtLevel=1 -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
