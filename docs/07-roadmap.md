# Roadmap

Cada fase termina con algo **verificable desde afuera**. "Está avanzado" no es un
estado; "responde 202 y el estado cambia a READY" sí.

El orden invierte deliberadamente dos pasos del plan original: el corte vertical
de ingesta entra **antes** del CRUD completo de usuarios. La razón es riesgo —
el CRUD es territorio conocido, la ingesta asíncrona es la que puede obligar a
rehacer decisiones. Se valida el cableado difícil temprano, cuando cambiar de
opinión todavía es barato.

---

## Fase 0 — El esqueleto levanta

Nada de negocio. Solo que `devherd serve` funcione.

- `compose.yaml` en la raíz con `edge`, `api`, `web`, `db`
- `.devherd.yml` apuntando a `edge:80`
- Backend Spring Boot con Actuator y nada más
- Frontend Vite con una página
- Caddyfile ruteando `/api` y `/`

**Hecho cuando:** `http://java-starter.localhost/` sirve la SPA con HMR
funcionando (editar un `.vue` se refleja sin recargar), y
`http://java-starter.localhost/api/actuator/health` responde `{"status":"UP"}`
con la base incluida en los detalles. Todo desde `devherd serve`, sin puertos
del host.

> Es la fase más ingrata y la que más problemas evita. No se avanza sin ella.

---

## Fase 1 — Base de datos e identidad

- Flyway con `V1__identity.sql`: `users`, `user_roles`, `refresh_tokens`
- Seed de dev: admin y usuario normal
- Spring Security: login, refresh con rotación, logout, `/api/auth/me`
- Cookies `at` y `rt`, CSRF con `XSRF-TOKEN`
- Rate limit de login (5 intentos / 15 min)
- `@RestControllerAdvice` con `ProblemDetail` + `traceId`
- `PageResponse<T>` y auditoría JPA, aunque todavía casi no se usen
- springdoc en `/api/openapi.json` y generación del cliente TS
- Frontend: `AuthLayout`, `AppLayout`, guards por `meta`, store de sesión,
  interceptor con refresh de una sola promesa
- Dashboard vacío que dice "hola, {displayName}"

**Hecho cuando:** login desde el navegador deja cookies `HttpOnly`, un F5 en una
ruta privada **no** rebota a login, el access token expira a los 15 minutos y la
siguiente petición se recupera sola vía refresh sin que el usuario lo note, y
`GET /api/auth/me` sin cookie devuelve un `problem+json` con `code:
UNAUTHENTICATED` y `traceId`.

> Aquí queda fijado el molde: errores, paginación, auditoría y contrato de
> sesión. Todo lo que venga después lo copia.

---

## Fase 2 — Corte vertical de ingesta (delgado)

Sin parsear nada. Solo mover bytes y estados.

- `V2__ingestion.sql`: tabla `uploads`
- `StorageService` + `LocalStorageService` sobre volumen
- `POST /api/uploads`: valida, guarda en streaming calculando SHA-256, deduplica,
  responde `202` con estado `RECEIVED`
- Los tres límites de tamaño (Caddy, Spring, parser) ya puestos
- Validación de MIME real con Tika
- `GET /api/uploads` paginado y `GET /api/uploads/{id}`
- Frontend: dropzone con progreso, listado con los cuatro estados, detalle

**Hecho cuando:** subir un CSV de 5 MB responde en menos de un segundo con un
id; el archivo existe en el volumen con el nombre correcto; subirlo dos veces
devuelve el mismo upload con `200`; subir un `.exe` renombrado a `.csv` responde
`415` con `UNSUPPORTED_TYPE`; y subir 30 MB corta en Caddy sin tocar la JVM.

> El estado se queda en `RECEIVED` para siempre y eso está bien. Lo que se
> valida aquí es el cableado, no el parseo.

---

## Fase 3 — Usuarios y roles

Ahora sí, el CRUD completo. Con el molde ya probado, es mecánico.

- `GET /api/users` paginado con búsqueda, `POST`, `GET`, `PATCH`, `DELETE` lógico
- Cambio de contraseña como endpoint propio
- Autorización `ADMIN` y la regla de "siempre queda un admin habilitado"
- Auditoría visible: quién creó y modificó cada usuario
- Frontend: listado, formularios, guard por rol

**Hecho cuando:** un `USER` que pide `GET /api/users` recibe `403`, un admin no
puede quitarse su propio rol (`409`), y el listado pagina correctamente con 200
usuarios sembrados.

---

## Fase 4 — Parseo asíncrono real

La fase difícil, con todo lo demás ya estable.

- `V3__dataset.sql`: `datasets`, `dataset_columns`, `dataset_rows` + índice GIN
- `@Async` con pool configurado explícitamente (no el ejecutor por defecto)
- Máquina de estados con `attempts` y `heartbeat_at`
- Reaper al arrancar y `@Scheduled` de barrido
- Lector CSV (Commons CSV) y XLSX (POI en modo evento)
- Inferencia de esquema en dos pases, con las reglas de
  [`03-ingesta.md`](03-ingesta.md#inferencia-de-esquema)
- Escritura por lotes de 1000 filas
- `POST /api/uploads/{id}/retry`
- Frontend: polling con retroceso, pausa al ocultar la pestaña, vista de error
  con el código traducido

**Hecho cuando:** un CSV de 100 000 filas pasa de `RECEIVED` a `READY` con la UI
reflejando el cambio sin recargar; matar el contenedor a mitad del parseo deja
el upload en `FAILED` con `INTERRUPTED` tras reiniciar (no colgado en
`PARSING`); un archivo con la columna `1,5` / `2,75` se infiere `DECIMAL`, y uno
con `1,5` y `1.5` mezclados queda `STRING` con `typeAmbiguous`.

> Los tests de inferencia se escriben **antes** que el inferidor. Es la única
> parte del proyecto donde el caso raro es la mayoría del trabajo.

---

## Fase 5 — Vista de tabla

- `GET /api/datasets/{id}` con columnas y tipos
- `GET /api/datasets/{id}/rows` paginado, ordenable, filtrable
- Frontend: tabla con formato por tipo, marca de tipo ambiguo, orden y filtro
  contra el servidor

**Hecho cuando:** navegar a la página 200 de un dataset de 100 000 filas
responde en menos de 300 ms, ordenar por una columna reordena el dataset
completo (no la página), y las columnas numéricas van alineadas a la derecha.

---

## Fase 6 — Gráficas

- `POST /api/datasets/{id}/query` con agregación en SQL, `limit`, `truncated`
- Validación de dimensión, medida y agregación contra `dataset_columns`
- Frontend: selector guiado por el esquema, ECharts, aviso de truncado

**Hecho cuando:** graficar suma por categoría sobre un dataset de 100 000 filas
transfiere menos de 50 KB al navegador, pedir `SUM` sobre una columna de texto
devuelve `422` (y la UI ni siquiera lo ofrece), y un dataset con 300 categorías
muestra "50 de 300" de forma visible.

---

## Fase 7 — Dashboard real

Ahora que hay datos que agregar.

- Endpoints existentes: conteo de uploads por estado, últimos datasets, filas
  totales
- Si hace falta un dato que ningún endpoint da, se agrega al módulo dueño de ese
  dato, **no** a un módulo "dashboard"

**Hecho cuando:** el dashboard no tiene backend propio y aun así muestra
actividad real del usuario en curso.

---

## Fase 8 — Endurecer (opcional)

Nada de esto bloquea un starter funcional:

- Compose y Dockerfiles de producción, imágenes multi-stage, usuario no root
- Rabbit o Redis Streams reemplazando `@Async` (el disparador, no la máquina de
  estados)
- SSE en vez de polling para el progreso
- Exportar el dataset filtrado a CSV
- MinIO detrás de `StorageService`
- `devherd observe` con Sentry

---

## Reglas del roadmap

- **Una fase, una incógnita.** Si una fase prueba dos cosas nuevas a la vez y
  falla, no sabes cuál fue. Ese error ya costó caro en otros proyectos.
- **Ninguna fase termina sin su test.** Las de backend con Testcontainers, las de
  frontend con Vitest.
- **No se salta la fase 0.** Es tentador y siempre se paga.
- **Si una fase se desvía del plan, se escribe una decisión en el vault** antes
  de seguir. Un plan que se erosiona en silencio deja de servir en dos semanas.
