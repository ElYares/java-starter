# Calidad, tests y observabilidad

## Testcontainers, no H2

Postgres real en los tests, en contenedor, arrancado una vez y reusado por toda
la suite (`@ServiceConnection` de Spring Boot 3.1+ hace el cableado solo).

H2 miente. No tiene `jsonb`, ni `citext`, ni índices GIN, ni el mismo
comportamiento de tipos. Un proyecto cuyo modelo de datos se apoya en JSONB no
puede testearse contra una base que no lo tiene. Ese es el argumento completo.

```java
@Testcontainers
@SpringBootTest
class DatasetQueryIT {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
}
```

**Los tests corren las migraciones de Flyway**, las mismas que producción. Así se
prueba el esquema real, y una migración rota se descubre en CI y no al
desplegar.

## Pirámide

| Nivel | Herramienta | Qué cubre |
|---|---|---|
| Unitario | JUnit 5 puro, sin Spring | Inferencia de tipos, máquina de estados, reglas de dominio |
| Slice | `@WebMvcTest`, `@DataJpaTest` + Testcontainers | Serialización, validación, consultas |
| Integración | `@SpringBootTest` + Testcontainers | Flujo completo de ingesta, autenticación |
| Frontend | Vitest + Testing Library | Composables, guards, interceptor |

**Donde más rinden los tests en este proyecto:**

1. **Inferencia de tipos.** Es la lógica con más casos raros y menos
   observabilidad. Se escribe la tabla de casos primero: separadores decimales
   mezclados, fechas ambiguas, columnas vacías, BOM, encabezados duplicados,
   celdas con comillas y saltos de línea dentro.
2. **La máquina de estados del upload.** Cada transición inválida debe fallar.
3. **Autorización por propiedad.** Un test por endpoint que confirme que el
   usuario A recibe `404` sobre un recurso del usuario B. Es la regla que se
   rompe al agregar el endpoint número quince y nadie nota.
4. **El interceptor de refresh.** Cinco peticiones que fallan simultáneamente
   deben producir **un** refresh, no cinco.

## Modulith: los límites se verifican

```java
class ModularityTests {
    static final ApplicationModules modules = ApplicationModules.of(StarterApplication.class);

    @Test void verifiesModularStructure() { modules.verify(); }

    @Test void writesDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
```

`modules.verify()` falla si `dataset` importa algo de `ingestion`, o si alguien
usa un paquete interno de otro módulo. Sin esto, "estructura por feature" es una
convención que se erosiona en tres semanas y nadie se entera hasta que hay que
tocar todo junto.

`Documenter` genera diagramas de los módulos y sus dependencias reales — no las
declaradas. Cuando el diagrama generado deja de parecerse al de
[`01-arquitectura.md`](01-arquitectura.md), uno de los dos está mal.

## CI

GitHub Actions, dos jobs en paralelo:

```
backend:  mvn verify   (Testcontainers funciona en el runner de ubuntu)
frontend: npm ci && npm run lint && npm run type-check && npm run test
```

Y un tercero que corre solo en `main`: construir las imágenes de producción. Que
compile no es lo mismo que que la imagen se arme.

**La regla de oro:** si CI pasa, `devherd serve` funciona. Cuando eso deja de
ser cierto, se arregla CI, no se ignora.

## Observabilidad

- **Actuator** expuesto: `/api/actuator/health` con detalle de la base,
  `/info` con la versión del build, `/metrics`. `health` es lo que usa el
  `healthcheck` del compose.
- **Logs estructurados en JSON.** Spring Boot 3.4+ lo trae nativo:
  `logging.structured.format.console=ecs`. Sin librerías extra.
- **`traceId` por request**, propagado con Micrometer Tracing, presente en cada
  línea de log **y en el cuerpo de cada error** que ve el frontend. Es lo que
  convierte un reporte de usuario en una búsqueda de treinta segundos.
- **Métricas propias del dominio**, que son las que de verdad se miran: duración
  del parseo por dataset, filas por segundo, conteo de uploads por estado, tasa
  de fallos por `error_code`.
- **`devherd observe`** como colector local estilo Sentry, opcional. Ver
  [`06-infra-devherd.md`](06-infra-devherd.md#observabilidad-opcional).

## Lo que no se testea

Ser explícito evita culpa difusa:

- **Los getters y setters.** Cobertura por cobertura no es una meta.
- **La UI pixel a pixel.** Los tests de frontend cubren comportamiento
  (composables, guards, estados), no maquetación.
- **Que ECharts dibuje.** Se testea que el endpoint devuelva la serie correcta;
  que la librería la pinte es su problema.
- **Playwright.** Vale la pena después de la fase 6, cuando hay un flujo
  completo que vale la pena bloquear contra regresiones. Antes es mantenimiento
  sin retorno.
