# printscript-runner

Valida, formatea, lintea, ejecuta y corre tests de PrintScript. Sin estado; también consume pedidos de Redis Streams.

Parte de Snippet Searcher. Contrato de la API: `contracts/printscript-runner.openapi.yaml` en el repo de documentación.

## Requisitos

- [Nix](https://nixos.org) con flakes. El dev shell trae JDK 21, Gradle, docker-compose y utilidades.
- Docker (para Testcontainers y para correr el sistema completo). En NixOS: `virtualisation.docker.enable = true;` en la configuración del sistema.
- Opcional: [direnv](https://direnv.net) para entrar al dev shell y cargar `.env` al hacer `cd` (`direnv allow` la primera vez).

## Uso

```sh
nix develop                # o direnv
cp .env.example .env       # completar los valores de Auth0
export GITHUB_ACTOR=<usuario> GITHUB_TOKEN=$(gh auth token)  # para bajar PrintScript
./gradlew check            # ktlint + tests (los de integración necesitan Docker)
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

- `common/security`: todo exige un JWT válido de Auth0 salvo `/health`.
- `common/logging`: `X-Correlation-Id` y el usuario (`sub`) en el MDC de cada línea de log.
- `common/http`: los `RestClient` reenvían el correlation id y el token del usuario a otros servicios.
- `common/error`: `BadRequestException`, `NotFoundException`, `ForbiddenException`, `ConflictException`, `UnprocessableContentException` (con propiedades extra, como `diagnostics`) y errores de validación como Problem Details.

## Ramas y CI

- `dev` y `prod`. Se trabaja en ramas cortas con pull request a `dev`; `prod` recibe merges desde `dev`.
- `ci.yml`: `./gradlew check` en cada pull request y push.
- `publish.yml`: en cada push a `dev` o `prod`, publica la imagen en GHCR como `ghcr.io/<owner>/printscript-runner:<rama>` y `:<rama>-<sha>`.

## Migrar a GitHub

```sh
gh repo create <owner>/printscript-runner --private --source . --remote origin
git push -u origin dev prod
gh repo edit <owner>/printscript-runner --default-branch dev
```

No hace falta configurar secretos: CI usa el `GITHUB_TOKEN` del workflow para bajar PrintScript y publicar en GHCR. El paquete de PrintScript tiene que darle acceso de lectura a este repo (Package settings → Manage Actions access) si no son del mismo dueño o el paquete es privado.
