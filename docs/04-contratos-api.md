# Contratos de API

Todo bajo `/api`. Todo con `Content-Type: application/json`, salvo la subida
(`multipart/form-data`) y los errores (`application/problem+json`).

El contrato lo genera springdoc en `/api/openapi.json`, y de ahí sale el cliente
TypeScript del frontend. Esa generación no es un lujo: elimina la clase entera
de bugs "el backend renombró un campo y el frontend se enteró en producción".

## Autenticación

Dos cookies de sesión, ambas `HttpOnly`, `Secure` en producción, `SameSite=Lax`,
y `Path` acotado — más una pista que no es una credencial:

| Cookie | Contenido | Vida | Path | `HttpOnly` |
|---|---|---|---|---|
| `at` | JWT de acceso firmado (HS256), claims `sub`, `roles`, `exp` | 15 min | `/api` | sí |
| `rt` | Refresh opaco (aleatorio de 256 bits) | 14 días | `/api/auth` | sí |
| `has_session` | Literalmente `1`. No lleva información | 14 días | `/` | **no** |

Como todo vive bajo el mismo origen gracias al proxy, el navegador manda las
cookies solo. **El frontend no toca tokens, no los guarda y no los adjunta.**
El debate localStorage-vs-cookie desaparece junto con el problema de XSS
robando el token. Ver Decisión 003.

El refresh es **opaco y consultable en base**, no un JWT: eso es lo que permite
revocarlo. Un refresh JWT no se puede invalidar sin una lista negra, que es una
tabla — la misma tabla, pero con más pasos.

### La pista de sesión

`has_session` es la única cookie que JavaScript puede leer, y existe por un
efecto secundario de que las otras dos no lo sean: **la SPA no tiene forma de
saber si alguna vez hubo sesión.** Sin saberlo, su interceptor responde al `401`
de `/auth/me` con un refresh que también falla, y cada visita anónima cuesta dos
peticiones perdidas y deja un `401` en el log que se parece a un ataque.

Con la pista, el visitante anónimo no pide nada.

**No es una credencial y el servidor jamás la mira.** Ponerla a mano no consigue
nada: quien lo haga solo logra que su propio navegador intente un refresh que va
a ser rechazado. Toda la autorización sigue viviendo en el `at` firmado.

Dos detalles que no son cosméticos:

- **Su `Max-Age` es el del `rt`, y se reemite en cada rotación.** Una pista que
  sobreviva al refresh token devuelve el refresh perdido que vino a evitar; una
  que caduque antes expulsa a un usuario con sesión válida.
- **La borra el logout, y también el frontend cuando un refresh es rechazado.**
  Ese segundo camino es del cliente a propósito: ahí ya sabe que la sesión murió,
  y resolverlo en el servidor obligaría a colgar cabeceras `Set-Cookie` de la
  excepción del refresh para algo que en el cliente es una línea.

Ver Decisión 014.

### CSRF

Cookie de autenticación implica CSRF. `SameSite=Lax` cubre casi todo, pero no es
suficiente por sí solo:

- Spring Security con `CookieCsrfTokenRepository.withHttpOnlyFalse()` emite una
  cookie `XSRF-TOKEN` legible por JS.
- Axios la lee y la reenvía en `X-XSRF-TOKEN` automáticamente
  (`xsrfCookieName` / `xsrfHeaderName`).
- Se exige en todo método mutante. `GET` queda exento.

### Endpoints

```
POST   /api/auth/login      {email, password}   → 204 + cookies at/rt
POST   /api/auth/refresh    (cookie rt)         → 204 + cookies rotadas
POST   /api/auth/logout     (cookie rt)         → 204 + cookies borradas
GET    /api/auth/me                             → {id, email, displayName, roles[]}
```

- `login` responde `204` sin cuerpo. El perfil se pide con `me`: un solo lugar
  que define qué sabe el frontend del usuario.
- `refresh` **rota**: invalida el `rt` recibido, emite uno nuevo y deja el
  `replaced_by` del anterior apuntando al nuevo. Si llega un `rt` que ya fue
  reemplazado,
  se revoca toda la cadena del usuario y se responde `401` — es la firma de un
  token robado.
- Límite de intentos: 5 fallos por email en 15 minutos → `429` con
  `Retry-After`. El contador es por email **y** por IP; solo por IP se saltea con
  NAT, solo por email permite bloquear a un tercero.

## Formato de error — RFC 7807

Spring Boot 3 trae `ProblemDetail` nativo. No hace falta librería; sí hace falta
un `@RestControllerAdvice` que lo unifique desde el primer commit.

```json
{
  "type": "https://java-starter.localhost/errors/validation",
  "title": "La petición no es válida",
  "status": 400,
  "detail": "Revisa los campos marcados",
  "instance": "/api/uploads",
  "code": "VALIDATION_FAILED",
  "traceId": "8f2c1a...",
  "errors": [
    {"field": "email", "code": "NotBlank", "message": "El email es obligatorio"}
  ]
}
```

- `code` es un enum estable. El frontend nunca decide con base en `title` ni en
  `detail`, que son texto para humanos y pueden cambiar.
- `traceId` va **siempre**, también en los 500. Es lo que convierte "no funciona"
  en un `grep` de treinta segundos.
- Un `500` nunca expone stack trace ni mensaje de excepción. El detalle vive en
  el log, indexado por `traceId`.

| HTTP | `code` típico | Cuándo |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation falló |
| 401 | `UNAUTHENTICATED` | Sin cookie válida, o refresh vencido |
| 403 | `FORBIDDEN` | Autenticado pero sin permiso, o no es dueño del recurso |
| 404 | `NOT_FOUND` | No existe, **o existe y no es tuyo** (ver abajo) |
| 409 | `CONFLICT` | Email duplicado, transición de estado inválida |
| 413 | `FILE_TOO_LARGE` | Excede el límite de subida |
| 415 | `UNSUPPORTED_TYPE` | MIME real no aceptado |
| 422 | `UNPROCESSABLE` | Sintaxis válida, semántica imposible |
| 429 | `TOO_MANY_REQUESTS` | Rate limit de login |
| 500 | `INTERNAL` | Todo lo demás |

**Recurso ajeno responde 404, no 403.** Un `403` confirma que el recurso existe,
y eso permite enumerar ids de otros usuarios. Solo se usa `403` cuando el
recurso es tuyo pero la operación requiere un rol que no tienes.

## Paginación

Un único envoltorio para toda colección, desde el día uno. Un endpoint sin
paginar no sobrevive al primer archivo real.

```json
{
  "content": [ ... ],
  "page": {"number": 0, "size": 20, "totalElements": 137, "totalPages": 7}
}
```

- Parámetros: `?page=0&size=20&sort=receivedAt,desc`.
- `size` con tope duro de 100. Sin tope, `?size=1000000` es un DoS de una línea.
- **DTO propio, no el `Page` de Spring serializado.** La forma JSON de `PageImpl`
  nunca fue contrato estable y Spring mismo lo advierte. Un `PageResponse<T>`
  propio cuesta veinte líneas y no cambia bajo tus pies al actualizar.

## Uploads

```
POST   /api/uploads              multipart, campo "file"    → 202 {id, status, filename, sizeBytes}
GET    /api/uploads?page&size    del usuario en curso       → PageResponse<UploadSummary>
GET    /api/uploads/{id}                                    → UploadDetail (incluye status, errorCode, datasetId)
POST   /api/uploads/{id}/retry   solo si status=FAILED      → 202
DELETE /api/uploads/{id}         borra archivo y dataset    → 204
```

- `202` porque el trabajo no terminó. Devolver `201` mentiría sobre el estado.
- Si el `sha256` ya existe para ese usuario, responde `200` con el upload
  existente en vez de duplicar. El cliente distingue por el código HTTP.
- `UploadDetail.datasetId` es `null` hasta que el estado sea `READY`. Es la señal
  que corta el polling.

## Datasets

```
GET  /api/datasets?page&size                → PageResponse<DatasetSummary>
GET  /api/datasets/{id}                     → metadatos + columnas con tipo inferido
GET  /api/datasets/{id}/rows?page&size&sort → PageResponse<Map<String,Object>>
POST /api/datasets/{id}/query               → serie agregada para graficar
```

`GET /api/datasets/{id}` es el que habilita todo lo demás del frontend: devuelve
las columnas con su `key`, `sourceName`, `inferredType` y `typeAmbiguous`. El
selector de gráficas se construye con esto — por eso el backend expone
**metadatos, no solo filas**.

### El endpoint de agregación

Es el que evita que las gráficas mueran con datos reales.

```json
POST /api/datasets/{id}/query
{
  "dimension": "ciudad",
  "measure":   "monto",
  "aggregate": "SUM",
  "filters":   [{"key": "anio", "op": "EQ", "value": 2025}],
  "limit":     50,
  "sort":      "VALUE_DESC"
}
```

```json
{
  "dimension": {"key": "ciudad", "type": "STRING"},
  "measure":   {"key": "monto", "type": "DECIMAL", "aggregate": "SUM"},
  "points":    [{"label": "Monterrey", "value": 128400.5}, ...],
  "truncated": true,
  "totalGroups": 312
}
```

Reglas que no son negociables:

- **La agregación ocurre en SQL.** El servidor devuelve como máximo `limit`
  puntos (tope 500). Mandar 200 000 filas para que el navegador las agrupe es
  cómo mueren estos dashboards. Ver Decisión 006.
- **`dimension`, `measure`, `op` y `aggregate` se validan contra
  `dataset_columns` y contra enums**, nunca se concatenan al SQL. El casteo
  (`(data->>'monto')::numeric`) lo arma el servidor desde el `inferred_type`
  guardado. Todo esto vive en una sola clase — es la única superficie de
  inyección del proyecto y por eso está concentrada.
- **`COUNT` no requiere `measure`.** `SUM`, `AVG`, `MIN` y `MAX` exigen una
  columna numérica o de fecha; pedir `SUM` sobre `STRING` es `422`, no un cero
  silencioso.
- **`truncated` es parte del contrato.** Si hay 312 grupos y se piden 50, la UI
  tiene que poder decir "mostrando 50 de 312". Truncar en silencio es mentir.

## Usuarios (ADMIN)

```
GET    /api/users?page&size&q      → PageResponse<UserSummary>
POST   /api/users                  → 201 + Location
GET    /api/users/{id}             → UserDetail
PATCH  /api/users/{id}             → 200
DELETE /api/users/{id}             → 204 (baja lógica: enabled=false)
```

- `PATCH`, no `PUT`. Un `PUT` obliga al cliente a mandar el objeto completo y
  cualquier campo omitido se borra.
- La contraseña no se cambia por aquí. Endpoint aparte
  (`POST /api/users/{id}/password`) que exige la contraseña actual cuando el
  usuario se cambia la propia.
- **Un admin no puede quitarse su propio rol `ADMIN` ni deshabilitarse.** El
  sistema debe conservar al menos un admin habilitado. Es la regla que se olvida
  y deja el sistema sin dueño.

## Reglas transversales

- **DTOs siempre.** Ninguna entidad JPA sale de un controlador. Con esquemas
  dinámicos esto es doblemente cierto, y además una entidad serializada arrastra
  el hash de la contraseña el día que alguien agregue un campo.
- **Autorización por propiedad.** Todo `findById` de un recurso de usuario lleva
  `AND owner_id = :currentUser` en la consulta, no un `if` después de cargarlo.
  El rol `ADMIN` amplía el alcance de forma explícita, en el repositorio. Ver
  Decisión 010.
- **Idempotencia donde importa.** Reintentar un upload no crea otro upload; el
  `sha256` lo garantiza.
