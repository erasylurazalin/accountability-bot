# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage. This never runs on era-server: a Gradle build of a Spring Boot
# app wants 1–2 GB and would fight Postgres and AdGuard for a 4 GB budget.
# Build on era-arch (or a GitHub-hosted runner) and ship the image over.
# ---------------------------------------------------------------------------
FROM gradle:9.7-jdk21 AS build
WORKDIR /src

# Dependency resolution is cached separately from source so a code-only change
# does not re-download the world.
COPY settings.gradle.kts build.gradle.kts ./
RUN gradle --no-daemon dependencies --quiet || true

COPY src ./src
RUN gradle --no-daemon bootJar --no-build-cache -x test

# ---------------------------------------------------------------------------
# Layer extraction. Spring Boot's layered jar puts dependencies in a different
# image layer from application classes, so a code-only rebuild reuses the
# dependency layer from cache. Pushed to a registry, only the few-megabyte
# application layer would travel; scripts/deploy.sh uses docker save instead,
# which ships every layer each time.
#
# If this step fails on a different Spring Boot version, the fallback is to skip
# this stage and COPY the fat jar straight into the runtime stage.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /extract
COPY --from=build /src/build/libs/*.jar app.jar
# The destination layout has moved between Spring Boot versions (some releases
# nest the layers under a directory named after the jar). Locate the layer root
# rather than hard-coding a path that silently breaks on an upgrade.
RUN java -Djarmode=tools -jar app.jar extract --layers --destination out \
 && LAYER_ROOT="$(dirname "$(find out -type d -name dependencies | head -1)")" \
 && test -n "$LAYER_ROOT" && test -d "$LAYER_ROOT/application" \
 && mv "$LAYER_ROOT" /layers \
 && ls /layers

# ---------------------------------------------------------------------------
# Runtime. era-server never sees a JDK, Gradle, or a Maven cache: the JRE and
# the application both live in here.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# apk upgrade picks up Alpine security fixes that landed after the base image
# was published. The Trivy scan in CI is what notices when they matter.
RUN apk upgrade --no-cache && addgroup -S app && adduser -S -G app app

COPY --from=extract /layers/dependencies/ ./
COPY --from=extract /layers/spring-boot-loader/ ./
COPY --from=extract /layers/snapshot-dependencies/ ./
COPY --from=extract /layers/application/ ./

USER app
EXPOSE 8080

# SerialGC beats G1 on this hardware: two cores, a small heap, and near-zero
# allocation rate. G1's concurrent threads and region bookkeeping cost memory
# and CPU that buy nothing at this scale.
ENV JAVA_TOOL_OPTIONS="\
-XX:+UseSerialGC \
-XX:MaxRAMPercentage=70 \
-Xss512k \
-XX:MaxMetaspaceSize=128m \
-Djava.security.egd=file:/dev/urandom \
-Duser.timezone=Asia/Almaty"

# Boot 3.3+ `jarmode=tools extract --layers` produces a restructured jar: a thin
# app.jar plus lib/, wired by the manifest Class-Path. That is launched with
# `java -jar`. JarLauncher belongs to the older `jarmode=layertools` layout and
# is not present in this image.
ENTRYPOINT ["java", "-jar", "app.jar"]
