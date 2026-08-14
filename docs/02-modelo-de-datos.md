# Modelo de datos

Todo el esquema lo administra Flyway. `spring.jpa.hibernate.ddl-auto` va en
`validate`, nunca en `update`: con Docker la base se recrea seguido y `update`
convierte cada `down -v` en una lotería.

## Convenciones

- Llaves primarias `UUID` (v7 si se puede, por localidad de índice). Un `id`
  autoincremental filtra volumen de negocio en las URLs.
- `snake_case` en la base, `camelCase` en Java. Sin prefijos de tabla.
- Timestamps `timestamptz`, siempre UTC. La zona se aplica al presentar.
- Toda tabla de negocio lleva `created_at`, `updated_at`, `created_by`,
  `updated_by`. Los llena Spring Data JPA Auditing, no el código de aplicación.
- Sin FK entre módulos que atraviesen el dominio en JPA. La FK existe en la
  base; en Java es un `UUID` suelto.

## Módulo `identity`

```sql
users (
  id              uuid primary key,
  email           citext not null unique,
  password_hash   text not null,          -- bcrypt, coste 12
  display_name    text not null,
  enabled         boolean not null default true,
  created_at, updated_at, created_by, updated_by
)

user_roles (
  user_id  uuid not null references users(id) on delete cascade,
  role     text not null,                 -- 'ADMIN' | 'USER'
  primary key (user_id, role)
)

refresh_tokens (
  id           uuid primary key,
  user_id      uuid not null references users(id) on delete cascade,
  token_hash   text not null unique,      -- SHA-256 del token, nunca el token
  issued_at    timestamptz not null,
  expires_at   timestamptz not null,
  revoked_at   timestamptz,
  replaced_by  uuid references refresh_tokens(id),  -- el sucesor, no el anterior
  user_agent   text,
  ip           inet
)
```

Notas que importan:

- **`citext` para el email.** Si no, `Yared@x.com` y `yared@x.com` son dos
  cuentas y lo descubres en producción.
- **Rol como tabla, no como enum de Postgres.** Agregar un rol a un enum
  requiere migración con `ALTER TYPE`; una fila no.
- **El refresh token se guarda hasheado.** Una fuga de la base no debe entregar
  sesiones activas.
- **`replaced_by` implementa rotación con detección de reuso.** Guarda el token
  que **sucede** a la fila, así que está nulo mientras no se haya rotado y la
  pregunta "¿este token ya se usó?" es una lectura de columna. Si llega un
  refresh token que ya fue reemplazado, es señal de robo: se revoca la cadena
  completa de ese usuario. El sentido lo fija la Decisión 012; antes apuntaba al
  revés y costaba una consulta por refresh.
- **La rotación doble la impide un `UPDATE` condicional, no un índice.** Con
  `replaced_by` apuntando hacia adelante, dos rotaciones simultáneas escriben la
  misma fila y un `UNIQUE` no las distingue.

## Módulo `ingestion`

```sql
uploads (
  id                     uuid primary key,
  owner_id               uuid not null references users(id),
  original_filename      text not null,
  declared_content_type  text,            -- lo que dijo el navegador
  detected_content_type  text,            -- lo que dijo Tika
  size_bytes             bigint not null,
  sha256                 text not null,
  storage_key            text not null,   -- ruta lógica dentro de StorageService
  status                 text not null,   -- RECEIVED | PARSING | READY | FAILED
  error_code             text,
  error_detail           text,
  attempts               int not null default 0,
  heartbeat_at           timestamptz,
  received_at            timestamptz not null,
  started_at             timestamptz,
  finished_at            timestamptz,
  created_at, updated_at, created_by, updated_by
)

create index on uploads (owner_id, received_at desc);
create unique index on uploads (owner_id, sha256) where status <> 'FAILED';
```

- **El índice único por `(owner_id, sha256)` deduplica.** Subir dos veces el
  mismo archivo devuelve el upload existente en vez de reprocesar. Excluye los
  fallidos para poder reintentar tras corregir el archivo.
- **`heartbeat_at` no es decorativo.** Es lo que permite distinguir "se está
  procesando" de "el contenedor murió a la mitad". Ver
  [`03-ingesta.md`](03-ingesta.md#recuperación).

## Módulo `dataset`

```sql
datasets (
  id            uuid primary key,
  upload_id     uuid not null unique references uploads(id),
  owner_id      uuid not null references users(id),
  name          text not null,
  row_count     bigint not null,
  column_count  int not null,
  source_format text not null,            -- CSV | XLSX
  delimiter     text,                     -- solo CSV
  sheet_name    text,                     -- solo XLSX
  has_header    boolean not null,
  created_at, updated_at, created_by, updated_by
)

dataset_columns (
  id              uuid primary key,
  dataset_id      uuid not null references datasets(id) on delete cascade,
  position        int not null,
  source_name     text not null,          -- el encabezado tal como venía
  key             text not null,          -- normalizado: la llave dentro del JSONB
  inferred_type   text not null,          -- STRING|INTEGER|DECIMAL|BOOLEAN|DATE|TIMESTAMP
  type_ambiguous  boolean not null default false,
  null_count      bigint not null,
  distinct_count  bigint,
  min_value       text,
  max_value       text,
  sample_values   jsonb,
  unique (dataset_id, position),
  unique (dataset_id, key)
)

dataset_rows (
  dataset_id  uuid not null references datasets(id) on delete cascade,
  row_number  bigint not null,
  data        jsonb not null,
  primary key (dataset_id, row_number)
)

create index on dataset_rows using gin (data jsonb_path_ops);
```

### Por qué JSONB y qué cuesta

Las tres alternativas eran tabla dinámica por dataset, JSONB, o dejar el archivo
y consultarlo bajo demanda. Se eligió JSONB porque no genera DDL en tiempo de
ejecución y sigue siendo consultable y agregable con SQL. El razonamiento
completo está en la Decisión 004.

Lo que hay que saber para no chocar:

- **`jsonb_path_ops` en vez del GIN por defecto.** Índice más chico y más rápido
  para el operador `@>`, que es el que usan los filtros por igualdad. No sirve
  para búsqueda de llaves sueltas, y no la necesitamos.
- **Agregar sobre JSONB exige casteo explícito.**
  `(data->>'monto')::numeric`. El tipo correcto sale de
  `dataset_columns.inferred_type`, y el casteo se arma en el servidor a partir
  de ahí — nunca a partir de un parámetro del cliente. Esa es la frontera de
  inyección SQL de este proyecto y está concentrada en un solo punto.
- **La llave del JSONB es `dataset_columns.key`, no el encabezado original.** El
  encabezado puede traer acentos, espacios, duplicados o venir vacío. La
  normalización (minúsculas, sin acentos, `_`, sufijo numérico ante colisión)
  ocurre una vez, al parsear, y queda registrada en `source_name`.
- **Los valores se guardan ya tipados en el JSON**: números como números,
  booleanos como booleanos, fechas como cadena ISO-8601. Guardar todo como texto
  obligaría a castear en cada consulta y perdería el índice.
- **Techo realista:** cientos de miles de filas por dataset. Con millones, el
  camino es materializar agregaciones o mover el dataset a columnas reales. No
  es problema del starter, pero está anotado para no fingir que escala infinito.

## Índices y su justificación

| Índice | Para qué |
|---|---|
| `uploads(owner_id, received_at desc)` | El listado paginado, que es la consulta más frecuente |
| `uploads(owner_id, sha256) unique where status<>'FAILED'` | Deduplicación de subidas |
| `dataset_rows(dataset_id, row_number)` (PK) | Paginación estable y ordenada por posición original |
| `dataset_rows using gin (data jsonb_path_ops)` | Filtros por igualdad sobre columnas del dataset |
| `dataset_columns(dataset_id, key) unique` | Garantiza que la llave del JSONB no colisiona |

No se agregan índices "por si acaso". Cada uno tiene una consulta que lo pide.

## Seed de desarrollo

Flyway con dos ubicaciones:

```yaml
spring.flyway.locations: classpath:db/migration          # siempre
# perfil dev añade:      classpath:db/migration,classpath:db/dev
```

`db/dev` crea el admin (`admin@local.test` / `admin`), un usuario normal y un
dataset de ejemplo ya parseado. Sin esto, cada `devherd down -v` cuesta diez
minutos de clics antes de poder probar nada.

El seed **nunca** se activa fuera del perfil `dev`, y el arranque falla si
detecta seed con perfil `prod`. Una credencial conocida en producción es la
forma más tonta de perder un sistema.
