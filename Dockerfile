# ========================
# STAGE 1: Build
# ========================
FROM maven@sha256:880934ae394bf91bc3e57d573e4fc04774f064f3c4df7ccd7cc10b3b126737bf AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# ========================
# STAGE 2: Runtime
# ========================
FROM eclipse-temurin:17-jre-alpine@sha256:27cc0849148c0fd32ee8e95988917becf9bc96a3182a24f99d9763aa8e90f8cb AS runtime

# CVE-2026-111497 (HIGH): brotli-libs 1.2.0-r1 da base; corrigido na 1.2.1-r0. Remover quando o digest da base já trouxer a correção.
RUN apk upgrade --no-cache brotli-libs     && addgroup -S -g 10001 oficina && adduser -S -D -u 10001 -G oficina oficina

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
RUN chown 10001:10001 app.jar

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=2s --start-period=120s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/execucao/actuator/health/liveness || exit 1

ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
