# syntax=docker/dockerfile:1
#
# Cistern — runnable Solid pod server.
#
#   docker build -t cistern .
#   docker run --rm -p 3000:3000 -v cistern-data:/data cistern
#
# SECURITY: this image has no authentication and no access control — Phases 4 and 5
# are not built. Anyone who can reach the port can read, write and delete every
# resource in the pod. Publish it to localhost or a private network only.

# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Poms first, so the dependency layer survives any source-only change.
COPY pom.xml .
COPY cistern-core/pom.xml                cistern-core/
COPY cistern-storage-file/pom.xml        cistern-storage-file/
COPY cistern-webflux/pom.xml             cistern-webflux/
COPY cistern-auth/pom.xml                cistern-auth/
COPY cistern-wac/pom.xml                 cistern-wac/
COPY cistern-mcp/pom.xml                 cistern-mcp/
COPY cistern-spring-boot-starter/pom.xml cistern-spring-boot-starter/
COPY cistern-app/pom.xml                 cistern-app/
# No BuildKit cache mount here on purpose: `--mount=type=cache` makes the image
# unbuildable on the legacy builder, and a self-hostable server should build on
# whatever Docker its operator already has. The pom-first COPY above is what
# actually caches dependencies, and it works on both builders.
RUN mvn -B -q dependency:go-offline -DskipTests

COPY . .
# Tests run in CI, not here: a container build is not the place to discover a red suite.
RUN mvn -B -q -DskipTests package \
 && cp cistern-app/target/cistern-app-*.jar /build/cistern.jar

# ---- runtime --------------------------------------------------------------
FROM eclipse-temurin:21-jre

# Non-root. /data is chowned before it becomes a volume so that a *named* volume
# inherits this ownership; a bind mount keeps the host's, which is the usual cause
# of "read-only" surprises — see docs/deploy.md.
RUN useradd --system --create-home --uid 10001 cistern \
 && mkdir -p /data \
 && chown cistern:cistern /data
COPY --from=build --chown=cistern:cistern /build/cistern.jar /app/cistern.jar

USER cistern
WORKDIR /app
VOLUME ["/data"]
EXPOSE 3000

ENV CISTERN_STORAGE_ROOT=/data \
    JAVA_OPTS=""

# cistern.base-url mints Location headers and the storage description, so it must be
# the URL clients actually call. Left at the localhost default behind a proxy or on
# Cloud Run, every URI the pod hands out names an origin nobody can reach.
#   docker run -e CISTERN_BASE_URL=https://pod.example.org ...

# A bare TCP check, deliberately: the storage root legitimately 404s until pods are
# provisioned (T5.4), so an HTTP-status healthcheck would call a healthy server dead.
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/3000' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/cistern.jar"]
