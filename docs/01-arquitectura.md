# Arquitectura

## Qué es este proyecto

Un starter de Spring Boot + Vue que se construye alrededor de un caso real:
**subir un archivo tabular, entenderlo, mostrarlo y graficarlo**. No es un CRUD
de ejemplo; el caso se eligió porque obliga a resolver procesamiento asíncrono,
esquemas dinámicos y agregación — que es donde los starters se rompen.

## Tres módulos, no cinco

La lista original era: login, altas de usuarios, dashboard, carga de archivos,
Excel para graficar. Se colapsa a tres:

| Módulo | Qué resuelve |
|---|---|
| `identity` | Quién eres y qué puedes hacer. Login, refresh, usuarios, roles |
| `ingestion` | Del archivo crudo a un dataset utilizable. Upload, validación, parseo, estados |
| `dataset` | Lo que se puede preguntar de un dataset. Esquema, filas paginadas, agregaciones |

Lo que **no** es un módulo:

- **Dashboard.** Es una vista que llama endpoints que ya existen. Darle backend
  propio significa duplicar consultas que ya viven en los otros módulos.
- **Excel vs CSV.** Es la misma tubería con dos lectores. Separarlos produce dos
  parsers, dos modelos y dos conjuntos de bugs.
- **Visualización.** El frontend es quien visualiza. Del lado del servidor solo
  hay un endpoint de agregación, y vive en `dataset` porque consulta datos de
  `dataset`.

Más un paquete transversal:

| `platform` | Manejo de errores, paginación, auditoría, seguridad compartida, configuración |

## Estructura por feature

```
api/src/main/java/dev/yares/starter/
  StarterApplication.java
  platform/
    error/          ProblemDetail advice, catálogo de códigos
    web/            PageResponse, argument resolvers, filtro de traceId
    security/       CurrentUser, config de Spring Security, CSRF
    audit/          JPA Auditing (created_by / updated_by)
  identity/
    api/            lo que otros módulos pueden usar (UserId, UserSummary)
    domain/         entidades y reglas
    infra/          repositorios JPA
    web/            controladores y DTOs
  ingestion/
    api/
    domain/
    infra/          storage, lectores CSV/XLSX
    web/
  dataset/
    api/
    domain/
    infra/
    web/
```

La regla es simple: **el paquete de nivel superior es el negocio, las capas van
adentro**. `controllers/`, `services/`, `repositories/` a nivel raíz agrupa por
detalle técnico y esconde de qué trata el sistema.

## Límites entre módulos

Un monolito modular sin límites verificados es un monolito con carpetas bonitas.
Las dependencias permitidas son:

```
identity   ──────────────┐
ingestion ──► dataset::api
todos     ──► platform
```

- **Nadie importa `identity` salvo `platform`.** La identidad del usuario en
  curso se obtiene de `platform.security.CurrentUser`, no inyectando
  `UserRepository` desde otro módulo.
- **`dataset` no conoce `ingestion`.** La ingesta escribe datasets a través de
  un puerto (`dataset.api.DatasetWriter`), no al revés.
- **Nada cruza fuera de `api/`.** Los paquetes `domain`, `infra` y `web` de un
  módulo son privados para el resto.
- **Sin relaciones JPA entre módulos.** `datasets.owner_id` es un `UUID`, no un
  `@ManyToOne User`. La integridad la garantiza una FK en la base; el código no
  navega el grafo. Esto es lo que permitiría extraer un módulo algún día.

Esto se **verifica en tests** con Spring Modulith, no se confía a la disciplina.
Ver [`08-calidad.md`](08-calidad.md).

## Stack

**Backend** — Java 21 (LTS), Spring Boot 3.5, Maven.

| Pieza | Elección | Por qué |
|---|---|---|
| Persistencia | Spring Data JPA + Postgres 16 | JSONB nativo para las filas |
| Migraciones | Flyway | Nunca `ddl-auto: update` |
| Seguridad | Spring Security, JWT en cookie HttpOnly | Mismo origen, sin token en JS |
| Módulos | Spring Modulith | Verifica los límites de arriba |
| API docs | springdoc-openapi | De ahí sale el cliente TS del frontend |
| Lectura CSV | Apache Commons CSV | Estable y suficiente |
| Lectura XLSX | Apache POI (API de eventos, streaming) | Un `.xlsx` cargado en memoria es un OOM |
| Detección MIME | Apache Tika | La extensión miente |
| Tests | JUnit 5 + Testcontainers | Postgres real, nunca H2 |

**Frontend** — Vue 3 (Composition API), Vite, Pinia, Vue Router, TypeScript.

| Pieza | Elección |
|---|---|
| Cliente HTTP | Axios con instancia única e interceptores |
| Gráficas | ECharts |
| Tests | Vitest + Testing Library |

**Infra local** — Docker Compose orquestado por devherd, con un servicio `edge`
(Caddy) que sirve todo bajo un solo origen. Ver
[`06-infra-devherd.md`](06-infra-devherd.md).

## Decisiones estructurales que ya están tomadas

Cada una tiene su nota en el vault con el razonamiento completo:

- Tres módulos, dashboard no es uno — Decisión 001
- Monolito modular verificado con Modulith — Decisión 002
- JWT en cookies HttpOnly sobre un solo origen — Decisión 003
- Las filas del dataset viven en JSONB — Decisión 004
- El estado del upload vive en la base, no en el hilo — Decisión 005
- Las gráficas se agregan en SQL, no en el navegador — Decisión 006
- devherd entra por un servicio `edge` dentro del compose — Decisión 007
- Java 21 y Spring Boot 3.5 — Decisión 008
- El archivo crudo vive detrás de `StorageService` — Decisión 009
- Autorización por propiedad, no solo por rol — Decisión 010

## Lo que este starter deliberadamente no hace

- **Microservicios.** El costo (red, despliegue, trazas distribuidas, consistencia)
  no se paga con este volumen. Los límites de módulo dejan la puerta abierta.
- **Cola de mensajes en la fase inicial.** Rabbit o Redis Streams entran solo si
  el disparador `@Async` deja de alcanzar. Lo que sí existe desde el día uno es
  la máquina de estados persistida, que es lo que hace ese cambio barato.
- **Multi-tenancy.** Hay propiedad por usuario (`owner_id`), no aislamiento por
  organización. Meter tenants después es una columna y un filtro más.
- **SSR.** SPA servida como estáticos. Nada en el caso de uso lo justifica.
