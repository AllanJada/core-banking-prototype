# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# 1. Build
#
# The JAXB plugin generates the ISO 20022 pain.001 / pacs.008 classes from the
# official XSDs during the build, so this stage needs the full source tree, not
# just the Java files.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-26-noble AS build

WORKDIR /build
COPY pom.xml ./
COPY src ./src

# The dependency cache lives outside the image, so a source change rebuilds the
# application without re-downloading Spring Boot, JAXB and Playwright each time.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package \
 && cp target/mldsa-*.jar /build/app.jar

# ---------------------------------------------------------------------------
# 2. Runtime
#
# Statements and payslips are rendered to PDF by headless Chromium through
# Playwright (see PdfGenerationService), so the runtime needs a browser and the
# ~80 system libraries it depends on — not just a JRE.
#
# Playwright's own image is used as the base and pinned to v1.62.0, the exact
# client version in pom.xml. That keeps the browser and the library driving it
# on the same version, which is the failure this pinning exists to prevent.
# It ships an older JDK, so the Java 26 runtime this application targets is
# copied in over the top; both images are Ubuntu Noble, so the JRE drops in.
# ---------------------------------------------------------------------------
FROM mcr.microsoft.com/playwright/java:v1.62.0-noble AS runtime

COPY --from=eclipse-temurin:26-jre-noble /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Only so the container can answer its own healthcheck.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/app.jar ./app.jar

# Created and chowned before any volume is mounted here: Docker seeds a fresh
# named volume from the image's directory, ownership included. Without this the
# volume mounts root-owned and the application cannot write a single file.
RUN mkdir -p /app/storage/files && chown -R pwuser:pwuser /app

# Headless Chromium refuses to start as root unless it is passed --no-sandbox,
# and PdfGenerationService asks for no such flag. Playwright ships this
# unprivileged user for exactly this situation.
USER pwuser

ENV APP_FILE_STORAGE_PATH=/app/storage/files
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

# This endpoint is deliberately anonymous — it is what the sign-in screen asks
# to decide whether to offer first-time setup — so it needs no credentials to
# prove the application is up and talking to the database.
# start-period is generous: Flyway runs every migration on first boot.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
    CMD curl -fsS http://localhost:8080/api/v1/users/bootstrap || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
