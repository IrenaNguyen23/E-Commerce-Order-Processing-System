# syntax=docker/dockerfile:1.7
# =====================================================================================
# CommerceFlow Inventory Service
#
# Multi-stage build (docker/standards.md). The build context is the repository ROOT because
# this module depends on the reactor parent and on commerceflow-common:
#
#   docker build -f services/inventory-service/Dockerfile -t commerceflow/inventory-service:1.0.0 .
# =====================================================================================

# ------------------------------------------------------------------ stage 1: build
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

# Copy only the POMs first so the dependency layer is reused whenever sources change.
COPY pom.xml ./
COPY common/commerceflow-common/pom.xml common/commerceflow-common/
COPY gateway/spring-cloud-gateway/pom.xml gateway/spring-cloud-gateway/
COPY services/auth-service/pom.xml services/auth-service/
COPY services/inventory-service/pom.xml services/inventory-service/
COPY services/order-service/pom.xml services/order-service/
COPY services/payment-service/pom.xml services/payment-service/
COPY services/notification-service/pom.xml services/notification-service/

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl services/inventory-service -am dependency:go-offline -DskipTests

COPY lombok.config ./
COPY common/commerceflow-common/src common/commerceflow-common/src
COPY services/inventory-service/src services/inventory-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl services/inventory-service -am clean package -DskipTests

# ------------------------------------------------------------------ stage 2: runtime
FROM eclipse-temurin:21-jdk AS runtime

LABEL org.opencontainers.image.title="commerceflow-inventory" \
      org.opencontainers.image.description="CommerceFlow Inventory Service" \
      org.opencontainers.image.vendor="CommerceFlow" \
      org.opencontainers.image.source="https://github.com/commerceflow/commerceflow"

# curl is required by the container HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Never run the JVM as root.
RUN groupadd --system --gid 1001 commerceflow \
    && useradd --system --uid 1001 --gid commerceflow --create-home commerceflow

WORKDIR /app

COPY --from=build --chown=commerceflow:commerceflow \
     /workspace/services/inventory-service/target/inventory-service.jar /app/app.jar

USER commerceflow

ENV SERVER_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SPRING_PROFILES_ACTIVE=docker

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
