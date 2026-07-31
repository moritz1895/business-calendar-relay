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

COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=12s --start-period=15s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
