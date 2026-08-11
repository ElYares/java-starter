# Índice de documentación — java-starter

Una línea por documento para poder elegir qué leer sin abrir todo.
Léelo antes que cualquier otro archivo.

| Archivo | Qué contiene | Cuándo leerlo |
|---|---|---|
| [`01-arquitectura.md`](01-arquitectura.md) | Los tres módulos, límites entre ellos, stack y por qué monolito modular | Antes de escribir la primera clase |
| [`02-modelo-de-datos.md`](02-modelo-de-datos.md) | Tablas, estados, JSONB, índices, auditoría | Antes de la primera migración |
| [`03-ingesta.md`](03-ingesta.md) | La tubería completa: validación, estados, inferencia de tipos, límites | Antes de la fase 4 |
| [`04-contratos-api.md`](04-contratos-api.md) | Endpoints, errores RFC 7807, paginación, autenticación por cookie, CSRF | Al conectar back y front |
| [`05-frontend.md`](05-frontend.md) | Estructura Vue, capa de API, layouts, guards, los cuatro estados | Antes de la primera vista |
| [`06-infra-devherd.md`](06-infra-devherd.md) | Compose, servicio `edge`, manifiesto de devherd, HMR detrás del proxy | Fase 0, y cada vez que algo no levanta |
| [`07-roadmap.md`](07-roadmap.md) | Fases en orden, con criterio de "hecho" verificable en cada una | Al empezar cualquier sesión |
| [`08-calidad.md`](08-calidad.md) | Testcontainers, Modulith, CI, observabilidad | Antes de la fase 1 |

## Dónde vive el pensamiento

Este directorio documenta **qué se construye y cómo**. El **por qué** de cada
decisión vive en el vault, no aquí:

- Estado de hoy: `10 Projects/java-starter/java-starter - Estado`
- Decisiones: `10 Projects/java-starter/Decisions/`
- Backlog (HU/CU): `10 Projects/java-starter/Backlog/`

Si un documento de aquí contradice una decisión del vault, gana el vault y este
archivo está desactualizado.
