# syntax=docker/dockerfile:1.7

# ---------- Etapa 1: compilación ----------
FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependencias primero (capa cacheable)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/api-sunat.jar extract --layers --launcher --destination target/extracted

# ---------- Etapa 2: ejecución ----------
FROM eclipse-temurin:21.0.12_8-jre-noble AS runtime

RUN groupadd --system app && useradd --system --gid app --home /app app \
    && apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

RUN mkdir -p /data/storage && chown -R app:app /data /app
USER app

ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC -Dfile.encoding=UTF-8"

EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
