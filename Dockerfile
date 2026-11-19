# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# PrintScript se baja de GitHub Packages, que pide token incluso para leer. Llega como secreto
# (docker/podman build --secret id=github_token,env=GITHUB_TOKEN) y no queda en ninguna capa.
# Se lee del archivo /run/secrets/github_token, que montan tanto BuildKit como Buildah (Podman).
ARG GITHUB_ACTOR=github-actions
ENV GITHUB_ACTOR=${GITHUB_ACTOR}
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN --mount=type=secret,id=github_token,required=true \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" ./gradlew dependencies --no-daemon > /dev/null
COPY src src
RUN --mount=type=secret,id=github_token,required=true \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)" ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
WORKDIR /app
# New Relic Java agent: only loaded with NEW_RELIC_ENABLED=true (docker-entrypoint.sh). Configured
# by environment: the license key comes at runtime; the logs it forwards carry the MDC.
ADD --chmod=644 https://repo1.maven.org/maven2/com/newrelic/agent/java/newrelic-agent/9.5.0/newrelic-agent-9.5.0.jar /app/newrelic/newrelic.jar
# The agent writes its own log next to the jar
RUN mkdir /app/newrelic/logs && chown app /app/newrelic/logs
ENV NEW_RELIC_ENABLED=false \
    NEW_RELIC_APP_NAME=printscript-runner \
    NEW_RELIC_APPLICATION_LOGGING_FORWARDING_CONTEXT_DATA_ENABLED=true
COPY --from=build /app/build/libs/app.jar app.jar
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh
USER app
# 9464: /prometheus, internal (METRICS_PORT)
EXPOSE 8080 9464
ENTRYPOINT ["/app/docker-entrypoint.sh"]
