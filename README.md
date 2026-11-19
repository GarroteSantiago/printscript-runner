# printscript-runner

Valida, formatea, lintea, ejecuta y corre tests de PrintScript. Sin estado; también consume pedidos de Redis Streams.

Parte de Snippet Searcher. Contrato de la API: `contracts/printscript-runner.openapi.yaml` en el repo de documentación.

## Requisitos

- [Nix](https://nixos.org) con flakes. El dev shell trae JDK 21, Gradle, docker-compose y utilidades.
- Contenedores: el dev shell trae Podman rootless y lo deja listo para `docker-compose` y Testcontainers (`DOCKER_HOST`), sin instalar nada en el sistema ni correr como root. Imágenes y contenedores quedan en `.podman/` (ignorado por git); para borrar todo: `podman unshare rm -rf .podman`. Ver `dev/podman-env.sh`.
- Opcional: [direnv](https://direnv.net) para entrar al dev shell y cargar `.env` al hacer `cd` (`direnv allow` la primera vez).

## Uso

```sh
nix develop                # o direnv
cp .env.example .env       # completar los valores de Auth0
export GITHUB_ACTOR=<usuario> GITHUB_TOKEN=$(gh auth token)  # para bajar PrintScript
./gradlew check            # ktlint + tests (los de integración levantan Postgres/Redis con Podman)
./gradlew ktlintFormat     # arreglar el formato
./gradlew bootRun          # levanta en :$PORT; /health responde sin token
```

El sistema completo se levanta desde `snippet-searcher-infra` con docker compose.

## Variables de entorno

| Variable | Para qué |
|---|---|
| `AUTH0_ISSUER_URI` | Issuer de Auth0, con la barra final |
| `AUTH0_AUDIENCE` | Audience de la API en Auth0 |
| `PORT` | Puerto HTTP (default 8080) |
| `REDIS_HOST`, `REDIS_PORT` | Redis de los streams (default `localhost:6379`) |
| `STREAMS_CONSUMER_NAME` | Opcional: nombre de consumidor en el grupo; por defecto el hostname del contenedor |
| `GITHUB_ACTOR` / `GITHUB_TOKEN` | Solo para compilar: credenciales de GitHub Packages (`read:packages`) |
| `LOG_FORMAT` | `json` (default: un objeto por línea, con `correlationId`, `user` y `service`) o `plain` (legible, para desarrollo local) |
| `METRICS_PORT` | Puerto interno de `/prometheus` (default 9464; `0` lo apaga). No pasa por NGINX |
| `NEW_RELIC_ENABLED` | `true` prende el agente Java de New Relic y el envío de métricas por OTLP (default `false`) |
| `NEW_RELIC_LICENSE_KEY` | License key (ingest) de New Relic; obligatoria con `NEW_RELIC_ENABLED=true` |
| `NEW_RELIC_OTLP_URL` | Opcional: endpoint OTLP de métricas (default `https://otlp.nr-data.net/v1/metrics`; en la región EU, `https://otlp.eu01.nr-data.net/v1/metrics`) |

## PrintScript

El lenguaje es la librería `org.printscript:toolchain` (GitHub Packages de `GarroteSantiago/PrintScript`), que GitHub sirve solo con token aunque sea para leer. `printscript/PrintScriptRunner` es el único lugar que ve tipos de la librería; el controller y los DTOs usan el modelo propio (`printscript/Model.kt`), así otro lenguaje sería otro runner.

- Endpoints síncronos: `GET /versions`, `GET /rules/{format|lint}`, `POST /validate`, `/format`, `/lint`, `/test`.
- Versiones del contrato: `1.0` y `1.1`; cualquier otra es 400.
- Las reglas (`printscript/RuleCatalog.kt`) se llaman como las claves de configuración JSON de la librería. Las que no vienen en `rules` cuentan como inactivas; una regla desconocida, un valor fuera de rango o dos reglas opuestas activas son 400.
- `/format` y `/lint` con código que no parsea: 422 con `diagnostics`. `/lint` tampoco acepta errores semánticos (la librería no analiza código inválido).
- `/test` corta con error si el snippet pide más `readInput` que los `inputs` dados.
- `/versions` trae `parserVersion`: la versión de `org.printscript:toolchain` que quedó en el classpath. Los jars de la librería no dicen su versión, así que el build la toma del grafo de dependencias resuelto y la escribe en `META-INF/build-info.properties` (`springBoot.buildInfo` en `build.gradle.kts`); sin ese archivo (por ejemplo, compilando desde un IDE sin Gradle) sale `unknown`.

## Redis Streams

Consume `snippet:jobs` (grupo `printscript-runner`, un consumidor por hostname) y publica en `snippet:results`, según `contracts/redis-streams.md`. Corre `VALIDATE`, `LINT`, `FORMAT` y `TEST` con el mismo adaptador que los endpoints.

- `common/streams`: `StreamClient` (los comandos que hacen falta, sobre Spring Data Redis; `XAUTOCLAIM` va directo por Lettuce porque Spring no lo tiene) y `StreamWorker` (lectura, reclamo, descarte y ack), que no sabe nada de PrintScript. `jobs/JobHandler` es lo propio del Runner.
- **Ack manual.** El job se confirma con `XACK` recién después de publicar el resultado con `XADD ... MAXLEN ~ 10000`.
- **Errores.** Una entrada inválida para el lenguaje (versión no soportada, reglas inválidas, código que no parsea en `LINT`/`FORMAT`) responde `FAILED` enseguida, porque reintentar da lo mismo. Cualquier otra falla (por ejemplo Redis caído al publicar) deja el job pendiente.
- **Reclamo.** Cada 30 s, `XAUTOCLAIM snippet:jobs printscript-runner <consumidor> 60000 0-0` sobre toda la lista de pendientes (de cualquier réplica, también una caída). Antes de procesar lo reclamado mira `XPENDING`: con más de 3 entregas publica un `FAILED` y hace `XACK`.
- Un job ilegible (sin `jobId`, tipo desconocido) se loguea y se confirma: no hay a quién contestarle.
- El `correlationId` del mensaje va al MDC mientras se procesa y se copia al resultado.
- El grupo se crea al arrancar (`MKSTREAM`, ignorando `BUSYGROUP`); si Redis no está, se reintenta. `streams.*` en `application.yaml` tiene los tiempos; los tests sin Redis lo apagan con `streams.enabled: false`.
- `RedisJobsIntegrationTest` usa Redis con Testcontainers (corre en CI); la lógica también está cubierta sin Docker con un Redis en memoria (`support/FakeStreamClient`). El dev shell trae `redis` (`redis-server`, `redis-cli`) para probar a mano.

## Ejecución interactiva (`/ws/run`)

WebSocket plano (sin STOMP) según `contracts/websocket.md`; solo lo usa el Snippet Service, que reenvía el token del usuario en el handshake (`Authorization: Bearer`, lo valida la cadena de seguridad HTTP) y ya chequeó el permiso.

- Un `start` por socket (otro cierra con `4409`). Cada `println` sale como `output` en el momento; cada `readInput` manda `input-request` y bloquea hasta el `input`. Termina con `done` (`ok`, `error` o `stopped`) y cierra el socket; `stop` o cerrar el socket cortan la ejecución.
- El código se valida antes de correr: si es inválido llega `error` con `diagnostics` sin ningún output.
- `run/InteractiveRun.kt` corre el programa en un hilo virtual y no ve tipos de la librería (usa `PrintScriptRunner.run` con callbacks). Límites (`interactive-run.*`), cada uno termina en `error` + `done error`:

| Propiedad | Variable | Default |
|---|---|---|
| `max-duration` (toda la ejecución, con las esperas) | `RUN_MAX_DURATION` | `60s` |
| `input-timeout` (espera de cada input) | `RUN_INPUT_TIMEOUT` | `5m` |
| `max-outputs` (mensajes `output`) | `RUN_MAX_OUTPUTS` | `10000` |
| `max-output-size` (bytes de output) | `RUN_MAX_OUTPUT_SIZE` | `1MB` |

- Mensajes entrantes de hasta 1 MiB (`server.servlet.context-parameters`); un cliente que no lee lo que se le manda (más de 512 KiB pendientes o 10 s bloqueado) pierde el socket.

## Qué trae la plantilla

- `common/security`: todo exige un JWT válido de Auth0 salvo `/health` (y `/prometheus` en el puerto interno).
- `common/logging`: `X-Correlation-Id` y el usuario (`sub`) en el MDC de cada línea de log.
- `common/observability`: el puerto interno de `/prometheus` (ver "Observabilidad").
- `common/http`: los `RestClient` reenvían el correlation id y el token del usuario a otros servicios.
- `common/error`: `BadRequestException`, `NotFoundException`, `ForbiddenException`, `ConflictException`, `UnprocessableContentException` (con propiedades extra, como `diagnostics`) y errores de validación como Problem Details.

## Observabilidad

- **Logs.** JSON por defecto (formato Logstash de Spring Boot, `logback-spring.xml`): cada línea trae `@timestamp`, `level`, `message`, `service` y el MDC (`correlationId` y `user`). `LOG_FORMAT=plain ./gradlew bootRun` vuelve a la línea legible de siempre; los tests usan `plain`.
- **Correlation ID.** `X-Correlation-Id` (o uno nuevo) en el MDC de cada request, reenviado en las llamadas HTTP (`common/http`) y devuelto en la respuesta.
- **Métricas** (Micrometer), con el tag `service`. Latencia y tráfico: `http.server.requests` (con histogramas, para percentiles entre réplicas) y `http.client.requests` (llamadas a otros servicios). Errores: los mismos, por `status` y `outcome`. Saturación: `jvm.*` (memoria, GC, threads), `process.cpu.usage`, `system.load.average.1m` y los comandos de Redis (`lettuce.*`).
- **Streams.** El consumidor de `snippet:jobs` pone el `correlationId` del job en el MDC mientras lo corre, y el resultado en `snippet:results` lleva el mismo (uno nuevo si el job no traía). En la ejecución interactiva llega en el `start` (o en el header del handshake) y queda en el MDC del hilo de la ejecución. Métricas: `stream.entries` (timer por `stream`, `group` y `outcome` = `ok`, `discarded` o `error`: tráfico, errores y latencia del consumidor) y los gauges `redis.stream.length`, `redis.stream.lag` (lo que el grupo todavía no leyó; Redis lo informa recién cuando el grupo se puso al día alguna vez) y `redis.stream.pending` (leído y sin ack, de `XINFO GROUPS`/`XPENDING`), por `stream` y `group`, de los dos streams: `snippet:jobs` (grupo `printscript-runner`) y también `snippet:results` (grupo `snippet-service`). Se consultan cada `observability.stream-metrics-interval` (15 s); NaN si Redis no responde o el grupo no existe.
- **`/prometheus`.** En el puerto interno `METRICS_PORT` (9464) responde sin token, para un scraper dentro de la red de Docker. En el puerto principal se rechaza siempre (`401`/`403`), así que tampoco se llega como `/api/prometheus` por NGINX. `/health` sigue igual en el puerto principal. (Tomcat atiende en los dos puertos; en el 9464 todo lo demás sigue pidiendo JWT.)
- **New Relic** (apagado hasta tener la license key). Con `NEW_RELIC_ENABLED=true` y `NEW_RELIC_LICENSE_KEY`:
  - la imagen carga el agente Java (`docker-entrypoint.sh`, agente 9.5.0 en `/app/newrelic`): APM (las cuatro señales doradas de HTTP, JVM y llamadas externas), distributed tracing y reenvío de los logs con su MDC (`NEW_RELIC_APPLICATION_LOGGING_FORWARDING_CONTEXT_DATA_ENABLED=true`). Se configura por variables `NEW_RELIC_*` (nombre de la app: `NEW_RELIC_APP_NAME`, por defecto el del servicio);
  - Micrometer exporta todas las métricas de arriba por OTLP (`management.otlp.metrics.*`, temporalidad delta, cada 60 s) con el header `api-key`, incluidas las propias que el agente no ve.
  - Para prenderlo: crear la license key (*Ingest - License*) en New Relic y pasar las dos variables al contenedor (compose o `docker secret` en Swarm). Fuera de Docker (`bootRun`) solo se prende el export OTLP; el agente se puede sumar con `-javaagent`.

## Ramas y CI

- `dev` y `prod`. Se trabaja en ramas cortas con pull request a `dev`; `prod` recibe merges desde `dev`.
- `ci.yml`: `./gradlew check` en cada pull request y push.
- `publish-image.yml`: en cada push a `dev` o `prod`, publica la imagen en GHCR como `ghcr.io/<owner>/printscript-runner:<rama>` y `:<rama>-<sha>`.

## Migrar a GitHub

```sh
gh repo create <owner>/printscript-runner --private --source . --remote origin
git push -u origin dev prod
gh repo edit <owner>/printscript-runner --default-branch dev
```

CI usa el `GITHUB_TOKEN` del workflow para bajar PrintScript y publicar en GHCR. Después de publicar dispara el despliegue de la rama en `snippet-searcher-infra`, con el secreto `DEPLOY_PAT` (token clásico con el scope `repo`). En Swarm, cada variable secreta llega como archivo: `VAR_FILE=/run/secrets/x` (`docker-entrypoint.sh`). El paquete de PrintScript tiene que darle acceso de lectura a este repo (Package settings → Manage Actions access) si no son del mismo dueño o el paquete es privado.
