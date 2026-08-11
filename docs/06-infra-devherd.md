# Infraestructura local con devherd

Todo Docker de este proyecto pasa por devherd. Nunca `docker compose` directo:
devherd calcula el nombre de proyecto Compose, aplica el proxy compartido y
corre el preflight de colisiones.

## Lo que hay que saber antes de escribir el compose

Cuatro hechos verificados sobre esta versión de devherd (`ee11431`) que
determinan la forma de la infra:

1. **`devherd scaffold` no soporta Java.** Los stacks reconocidos son
   `vue+flask`, `laravel`, `vue`, `flask`, `node` y `go`. El compose y el
   manifiesto se escriben a mano. No es un problema: `up`, `serve`, `plan`,
   `inspect`, `logs` y `down` funcionan igual sobre cualquier compose.
2. **El detector exige un compose o un `Dockerfile` en la raíz del repo.** Si el
   compose vive en `infra/`, `devherd up` funciona pero `park` no registra el
   proyecto, y entonces `proxy apply`, `open` y `logs <nombre>` dejan de
   encontrarlo. Por eso `compose.yaml` va en la raíz.
3. **`proxy` apunta a un solo servicio y un solo puerto.** No hay reglas por
   ruta en el manifiesto. Como necesitamos `/api` al backend y el resto al
   frontend, el ruteo tiene que ocurrir **dentro** del compose, en un servicio
   propio.
4. **`proxy.port` es el puerto interno del contenedor**, no el publicado en el
   host. El mapeo `ports:` es irrelevante para el proxy.

## Topología

```
                    http://java-starter.localhost
                                │
                    Caddy compartido de devherd (red infra_web)
                                │
                        ┌───────▼────────┐
                        │  edge  (Caddy) │   puerto interno 80
                        └───┬────────┬───┘
                  /api/*    │        │   todo lo demás
                        ┌───▼───┐ ┌──▼────┐
                        │  api  │ │  web  │
                        │ :8080 │ │ :5173 │
                        └───┬───┘ └───────┘
                            │
                        ┌───▼───┐
                        │  db   │  postgres:16
                        └───────┘
```

El servicio `edge` resuelve tres cosas de un golpe:

- **CORS deja de existir.** Un solo origen, sin preflights, sin
  `@CrossOrigin`, sin semanas peleando con `Access-Control-Allow-*`.
- **Las cookies `HttpOnly` funcionan sin `SameSite=None`.** Mismo origen,
  `SameSite=Lax` alcanza. Este es el habilitador del modelo de sesión completo.
- **Le da a devherd el único servicio que sabe apuntar.**

## Archivos en la raíz

```
compose.yaml                    ← el stack; el detector lo exige aquí
.devherd.yml                    ← manifiesto
.devherd.proxy.override.yml     ← lo genera devherd; no editar
docker/
  edge/Caddyfile
  api/Dockerfile                ← multi-stage: target dev y target prod
  web/Dockerfile
.env.example
.env                            ← no se commitea
```

## `.devherd.yml`

```yaml
version: 1
compose:
  files:
    - compose.yaml
proxy:
  domain: java-starter.localhost
  service: edge
  port: 80
```

| Campo | Nota |
|---|---|
| `version` | Se parsea pero no se valida. Convención de los demás proyectos |
| `compose.files` | Obligatorio y no puede ir vacío: una lista vacía es error, no cae a autodetección. Rutas relativas a la raíz y deben existir |
| `proxy.domain` | Si se omite, devherd deriva `<proyecto>.<tld>`. Explícito porque cuesta nada |
| `proxy.service` | `edge`. **No `api`** — el backend no sirve el frontend |
| `proxy.port` | `80`, el interno del contenedor de Caddy |

`proxy.service` y `proxy.port` **solo surten efecto juntos**. Si falta uno,
devherd cae a reglas por framework que no cubren este stack.

devherd genera aparte `.devherd.proxy.override.yml`, que cuelga `edge` de la red
externa `infra_web` con un alias. Ese archivo es suyo; no se edita a mano.

## El Caddyfile del `edge`

```
{
	# El healthcheck del contenedor golpea la API de administracion cada 10s y
	# Caddy la registra en info. Sin esta exclusion, `devherd logs` queda
	# sepultado bajo el latido de su propio healthcheck.
	log {
		exclude admin.api
	}
}

:80 {
	request_body {
		max_size 25MB
	}
	encode gzip

	handle /api/* {
		reverse_proxy api:8080
	}
	handle {
		reverse_proxy web:5173
	}
}
```

- Caddy pasa WebSockets de forma transparente, así que el HMR de Vite funciona
  sin configuración extra del lado del proxy.
- `max_size` es la **primera** de las tres capas de límite de subida. Corta la
  conexión antes de gastar un hilo de la JVM. Las otras dos están en
  [`03-ingesta.md`](03-ingesta.md#validaciones-y-límites).
- En producción, `handle` sirve estáticos desde el build de Vite en vez de
  proxear a `web`. Es la única diferencia de ruteo entre entornos.

## El compose de desarrollo

Puntos que definen la experiencia:

- **`api`** corre sobre `eclipse-temurin:21-jdk` con `./mvnw spring-boot:run`,
  el código montado por bind mount y `spring-boot-devtools` activo. Reinicio
  automático al recompilar. El repositorio Maven va a un volumen nombrado; sin
  eso, cada `up` vuelve a bajar medio internet.
- **`web`** sobre `node:22-alpine` con `npm run dev`; el `host: '0.0.0.0'` va
  en `web/vite.config.ts`, no en la linea de comandos. Es el unico servicio con
  Dockerfile propio, y por una sola razon: Docker hereda el propietario del
  directorio que existe **en la imagen** al crear un volumen nombrado, asi que
  `/workspace/node_modules` tiene que existir ahi con el UID del host o
  `npm install` falla con EACCES. El
  `node_modules` va en un volumen, no en el bind mount: montar el
  `node_modules` del host dentro del contenedor rompe los binarios nativos.
- **`db`** postgres:16-alpine con volumen persistente y **sin `ports:`
  publicados**. Si necesitas un cliente SQL, `devherd` ya te da la red; exponer
  5432 al host es cómo colisionan tres proyectos a la vez.
- **`healthcheck` en `db` y `api`**, con `depends_on: condition:
  service_healthy`. Sin esto, el backend arranca antes que Postgres, Flyway
  falla, y el contenedor entra en bucle de reinicio.
- Variables desde `.env`, con `.env.example` versionado. Sin defaults para
  secretos: que clonar y correr sin copiar el `.env` falle rápido y con mensaje
  claro es mejor que arrancar con una llave de firma conocida.

## Vite detrás del proxy

El detalle que muerde y no da error claro — el HMR simplemente queda muerto:

```ts
// web/vite.config.ts
server: {
  host: '0.0.0.0',
  port: 5173,
  strictPort: true,
  allowedHosts: ['java-starter.localhost'],
  hmr: {
    clientPort: 80,           // el navegador habla con el proxy, no con Vite
    host: 'java-starter.localhost',
  },
}
```

Sin `clientPort: 80`, el cliente de HMR intenta abrir el WebSocket contra el
5173 del host, que no está publicado.

`allowedHosts` hoy es redundante — Vite acepta cualquier host bajo `.localhost`
por omisión — pero deja de serlo en cuanto el dominio no termine en `.localhost`.
Se deja explícito porque el día que cambie, el síntoma es un `403` sin
explicación.

### Tres trampas de `devherd` que cuestan una tarde

- **Parquea el proyecto, no el directorio padre.** `devherd park ~/develop/personal`
  registra de paso todo lo que parezca proyecto, incluido el propio directorio
  padre y repos sin compose.
- **`devherd proxy apply` era todo o nada** — arreglado el 2026-08-10 en
  `internal/cli/proxy.go`. Recorría *todos* los proyectos registrados y abortaba
  en el primero sin compose o metadata de proxy, sin escribir las rutas de los
  demás; un repo ajeno mal registrado dejaba tu dominio sin ruta. Ahora los
  omite con un `WARN` y los reporta como `skipped:`. **Requiere devherd
  `c2f840c` o posterior**; con uno anterior la salida es una ruta manual en
  `~/.local/share/devherd/local_proxy/Caddyfile` más
  `docker exec infra_caddy caddy reload --config /etc/caddy/Caddyfile`, que
  además hay que rehacer después de cada `devherd down`.
- **El síntoma engaña**: un host sin bloque de ruta responde **200 con cuerpo
  vacío**, no 404. Verificar el dominio con `curl -o /dev/null -w %{http_code}`
  da un falso positivo. Hay que mirar el cuerpo o `%{size_download}`.

## Flujo de trabajo

```bash
devherd park ~/develop/personal/java-starter   # el proyecto, NO el directorio padre
devherd serve                            # up + proxy apply + open
devherd logs -f                          # toma una RUTA, no un nombre
devherd inspect                          # preflight de colisiones
devherd plan                             # ver el stack resuelto sin levantar
devherd down                             # bajar
```

`devherd doctor` valida los prerrequisitos del host cuando algo huele raro.

## Producción

Mismo repositorio, compose distinto. Las diferencias reales:

| Aspecto | Dev | Prod |
|---|---|---|
| `api` | `mvn spring-boot:run` + bind mount + devtools | Multi-stage: build Maven → `eclipse-temurin:21-jre-alpine`, JAR, usuario no root |
| `web` | Servidor de Vite con HMR | `vite build` → estáticos servidos por el propio `edge` |
| `db` | Volumen local, sin puertos publicados | Servicio gestionado o volumen respaldado; jamás expuesto |
| Secretos | `.env` en el repo del desarrollador | Inyectados por el entorno; nunca en la imagen |
| Perfil | `dev`, con seed | `prod`; el arranque **falla** si detecta el seed |

Lo que **no** cambia entre entornos: el ruteo `/api` vs `/`, el esquema de la
base y las migraciones. Si eso divergiera, el "funciona en mi máquina" volvería
por la puerta grande.

## Observabilidad opcional

devherd trae `devherd observe`, un colector local compatible con Sentry que
inyecta `SENTRY_DSN` en los servicios mediante un override propio. Si se quiere
usar, basta agregar `sentry-spring-boot-starter` al backend y leer el DSN del
entorno; si la variable no está, el starter no hace nada. Queda como mejora, no
como requisito de arranque.
