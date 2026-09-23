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
# Previously based on Playwright's own image, which bundles Chromium, Firefox,
# WebKit and ffmpeg as one 1GB+ layer — everything this application never
# launches, plus the one browser it does. On a connection that stalls on large
# single blobs, that one giant layer is also the single worst-case shape to
# pull: one failure loses the whole gigabyte, with nothing to resume from.
#
# Instead: a plain JRE, with only Chromium installed via Playwright's own
# installer, pinned to v1.62.0 to match the client in pom.xml — same guarantee
# as before (browser and library on one version), same PdfGenerationService
# comment's one-time-setup command, just run at build time instead of by
# hand. The install is real apt packages plus one browser download, so a
# stall loses a few megabytes at most, not the whole fetch.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:26-jre-noble AS runtime

# Node only exists to run `npx playwright install`, which is what actually
# resolves and fetches the right Chromium build and its exact OS dependency
# list for this Playwright version — hand-copying that list here would drift
# from it silently on the next version bump. Left in the final image rather
# than stripped in a later stage: stripping it means hand-picking which apt-
# installed shared libraries Chromium needs at runtime, which is exactly the
# kind of fragile, silently-wrong list this approach exists to avoid. Node
# costs tens of MB against Playwright's bundled image's 1GB+; not worth the
# risk to save it.
#
# NodeSource's package, not Ubuntu Noble's: Noble ships Node 18, and
# Playwright 1.62.0 requires >=20 — confirmed by the install failing outright
# on 18. Noble's nodejs/npm are also Debian-repackaged, which splits every
# npm dependency into its own .deb (390 packages here, pulling in eslint,
# webpack and babel for what only ever needs to run one install command).
# NodeSource ships upstream Node with npm bundled inside it, so this is both
# the version that actually works and a fraction of the package count.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates gnupg \
 && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

# No pwuser here — that was Playwright's base image's unprivileged user, and
# there is no equivalent on a plain JRE base, so one is created directly, and
# before the install below rather than after: chowning a ~1GB browser
# directory in a later RUN doesn't edit those files in place, it duplicates
# all of them into a new layer just to flip ownership bits — doubling this
# stage's actual cost for nothing. Doing it inside the same RUN as the
# install instead means the files land with their final ownership already,
# in one layer.
RUN useradd --create-home --shell /usr/sbin/nologin app

# A fixed path rather than the default (a user's home cache dir), so the
# same location is used whichever user installs versus runs — see below.
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

# --with-deps installs Chromium's shared library dependencies via apt as well
# as the browser itself, both for exactly this Playwright version, still as
# root. This is the whole install — no separate PdfGenerationService setup
# step is needed once the image is built.
#
# Retried on failure: apt goes through the same container network path and
# resolves fine, so a lookup failure here for cdn.playwright.dev or
# storage.googleapis.com specifically is transient flakiness on those hosts
# rather than DNS being unreachable outright — Playwright's own installer
# does not retry a failed browser download itself, so this does it instead.
RUN set -e; \
    ok=""; \
    for attempt in 1 2 3 4 5; do \
      if npx -y playwright@1.62.0 install --with-deps chromium; then ok=1; break; fi; \
      echo "playwright install failed (attempt $attempt/5), retrying in 10s..." >&2; \
      sleep 10; \
    done; \
    [ -n "$ok" ] \
 && chown -R app:app "$PLAYWRIGHT_BROWSERS_PATH" \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# --chown here for the same reason: ownership set as the file is written,
# not duplicated afterward. The jar is small, so this one was cheap either
# way, but it costs nothing to be consistent.
COPY --from=build --chown=app:app /build/app.jar ./app.jar

# Created and chowned before any volume is mounted here: Docker seeds a fresh
# named volume from the image's directory, ownership included. Without this the
# volume mounts root-owned and the application cannot write a single file.
# Only /app/storage/files is created here — everything else under /app was
# already owned correctly by the COPY above.
RUN mkdir -p /app/storage/files && chown -R app:app /app/storage

USER app

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
