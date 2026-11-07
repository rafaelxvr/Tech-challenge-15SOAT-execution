# ========================
# STAGE 1: Build
# ========================
FROM maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Agente New Relic: artefato imutável com checksum verificado (mesma versão do os-service).
FROM maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf AS newrelic-agent
ARG NEW_RELIC_JAVA_AGENT_VERSION=9.4.0
ARG NEW_RELIC_JAVA_AGENT_SHA256=1f8f42d25e6565a1a7088543deeeefacdd2b028761ac94743dd1715cf7ddf5f4
RUN mvn -B org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy       -Dartifact=com.newrelic.agent.java:newrelic-agent:${NEW_RELIC_JAVA_AGENT_VERSION}:jar       -DoutputDirectory=/opt/newrelic &&     mv /opt/newrelic/newrelic-agent-${NEW_RELIC_JAVA_AGENT_VERSION}.jar /opt/newrelic/newrelic.jar &&     echo "${NEW_RELIC_JAVA_AGENT_SHA256}  /opt/newrelic/newrelic.jar" | sha256sum -c -

# ========================
# STAGE 2: Runtime
# ========================
FROM eclipse-temurin:17-jre-alpine@sha256:27cc0849148c0fd32ee8e95988917becf9bc96a3182a24f99d9763aa8e90f8cb AS runtime

# CVEs HIGH do Alpine (brotli-libs, openssl, libexpat) presentes no digest da base; apk upgrade completo traz as correções. Revisar quando o digest da base já as incluir.
RUN apk upgrade --no-cache     && addgroup -S -g 10001 oficina && adduser -S -D -u 10001 -G oficina oficina

WORKDIR /app
COPY --from=newrelic-agent --chown=10001:10001 /opt/newrelic/newrelic.jar /app/newrelic/newrelic.jar
COPY --chown=10001:10001 docker/entrypoint.sh /app/entrypoint.sh
COPY --from=builder /app/target/*.jar app.jar
RUN chown 10001:10001 app.jar

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=2s --start-period=120s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/execucao/actuator/health/liveness || exit 1

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Observabilidade desligada por padrão; o overlay liga com OBSERVABILITY_ENABLED=true e injeta
# NEW_RELIC_LICENSE_KEY por Secret (a imagem nunca contém a chave).
ENV NEW_RELIC_APP_NAME=oficina-execution-service
ENV OBSERVABILITY_ENABLED=false
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
