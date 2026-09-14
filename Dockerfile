FROM ghcr.io/graalvm/native-image-community:25 AS builder

ARG MAVEN_VERSION=3.9.9
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    -o /tmp/maven.tar.gz \
    && tar -xzf /tmp/maven.tar.gz -C /opt \
    && rm /tmp/maven.tar.gz
ENV PATH="/opt/apache-maven-${MAVEN_VERSION}/bin:${PATH}"

WORKDIR /app
COPY settings.xml pom.xml ./
RUN mvn -s settings.xml dependency:go-offline -q

COPY src ./src
# Dummy required-with-no-default config: spring-boot-maven-plugin's process-aot goal
# (wired in automatically by the "native" profile, see pom.xml) actually boots the Spring
# context to generate AOT artifacts, so it needs the same required config as a real run,
# even though nothing here ever connects anywhere during the build.
# -Dnet.bytebuddy.experimental=true: bytebuddy 1.17.8 doesn't officially support Java 25
# bytecode yet; both the native profile's hibernate-enhance-maven-plugin and its own
# build-time class-init trick need this to accept this project's Java 25 bytecode. See
# docs/spikes/graalvm-native-image.md for the full history of this build's requirements.
RUN SMTP_HOST=localhost SMTP_USERNAME=x SMTP_PASSWORD=x \
    RELAY_ORGANIZER_EMAIL=o@example.com RELAY_ATTENDEE_EMAIL=b@example.com \
    RELAY_FROM_ADDRESS=r@example.com RELAY_REPLY_TO_ADDRESS=o@example.com \
    mvn -s settings.xml -Pnative -DskipTests -Dnet.bytebuddy.experimental=true -q native:compile

# ghcr.io/graalvm/native-image-community:25 is Oracle Linux 10.1 (glibc 2.39); ubuntu:24.04
# ships the same glibc major/minor, so the dynamically-linked native binary runs unchanged.
FROM ubuntu:24.04 AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# Fixed, explicit UID/GID (not an auto-assigned value, which isn't guaranteed stable
# across base-image versions) so a host bind-mount for /app/data (see
# docker-compose.yml) can be chowned to a predictable, documented owner on the host side.
RUN groupadd -g 10001 relay && useradd -u 10001 -g relay -M -s /usr/sbin/nologin relay \
    && mkdir -p /app/data && chown -R relay:relay /app
USER relay

# Log timestamps (see log4j2.xml) are otherwise unanchored: the base image defaults to
# UTC, which doesn't match the deployer's own wall clock when correlating a sporadic log
# entry against "when did I notice this". java.time resolves TZ the same way in a native
# image as on the JVM. Overridable per deployment via the TZ environment variable.
ENV TZ=Europe/Berlin

COPY --from=builder /app/target/business-calendar-relay ./business-calendar-relay

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=12s --start-period=15s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# Unlike the JVM, native-image binaries start with a fixed serial GC and no JIT compiler
# tiers to tune away -- the elaborate heap/GC/thread flags a JVM deployment needs (see
# git history) don't apply here. RELAY_NATIVE_OPTS is a plain passthrough for the rare
# case a native-image runtime flag (e.g. -Xmx) is ever needed; empty by default.
ENV RELAY_NATIVE_OPTS=""

ENTRYPOINT ["sh", "-c", "exec ./business-calendar-relay $RELAY_NATIVE_OPTS"]
