# java-starter

Starter de **Spring Boot 3.5 (Java 21) + Vue 3**, construido alrededor de un caso
real: subir un archivo tabular (CSV/XLSX), parsearlo en segundo plano, mostrarlo
en tabla y graficarlo.

No es un CRUD de ejemplo. El caso se eligió porque obliga a resolver
procesamiento asíncrono con recuperación, esquemas dinámicos y agregación —
que es donde los starters se rompen.

## Estado

**Fase 0 completa.** `devherd up` levanta `db`, `api`, `web` y `edge`; la SPA se
sirve en `http://java-starter.localhost/` con HMR funcionando y el API responde
bajo `/api`, sin un solo puerto publicado en el host. Sin lógica de negocio
todavía. Siguiente: fase 1 (base de datos e identidad),
[`docs/07-roadmap.md`](docs/07-roadmap.md).

> Nota: requiere devherd `c2f840c` o posterior (arreglo de `proxy apply`); con
> versiones anteriores, la ruta del dominio hay que ponerla a mano en el
> Caddyfile compartido. Ver la sección de trampas en
> [`docs/06-infra-devherd.md`](docs/06-infra-devherd.md).

## Arrancar

Requiere [devherd](https://github.com/ElYares/devherd) y Docker.

```bash
cp .env.example .env
devherd serve
```

- Aplicación: `http://java-starter.localhost`
- API: `http://java-starter.localhost/api`
- Health: `http://java-starter.localhost/api/actuator/health`

Nunca `docker compose` directo: devherd calcula el nombre del proyecto Compose,
aplica el proxy compartido y corre el preflight de colisiones.

## Documentación

Empieza por [`docs/INDEX.md`](docs/INDEX.md). Los ocho documentos cubren
arquitectura, modelo de datos, la tubería de ingesta, contratos de API,
frontend, infraestructura, roadmap y calidad.

El **por qué** de cada decisión vive en el vault de Obsidian, en
`10 Projects/java-starter/Decisions/`.

## Módulos

| Módulo | Qué resuelve |
|---|---|
| `identity` | Login, refresh, usuarios, roles |
| `ingestion` | Del archivo crudo a un dataset utilizable |
| `dataset` | Esquema, filas paginadas, agregaciones |

El dashboard no es un módulo: es una vista que consume endpoints existentes.
