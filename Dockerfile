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
COPY --from=build /app/build/libs/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
