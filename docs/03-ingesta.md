# La tubería de ingesta

Es la parte difícil del proyecto. Todo lo demás es CRUD.

## Principio

**El request HTTP no parsea.** Recibe, valida barato, guarda bytes, crea una fila
con estado `RECEIVED` y responde `202` con un id. El parseo ocurre después, en
otro hilo, y su avance vive en la base de datos.

Un `POST` que bloquea 40 segundos falla por timeout del proxy, no da feedback,
no se puede reintentar y no se puede observar. El costo de hacerlo asíncrono es
una tabla de estados; el beneficio es todo lo anterior.

## Flujo completo

```
POST /api/uploads (multipart)
   │
   ├─ 1. Validación barata (antes de leer el cuerpo completo)
   │     tamaño declarado, extensión, propietario autenticado
   │
   ├─ 2. Escritura a StorageService en streaming
   │     calculando SHA-256 y tamaño real al vuelo
   │
   ├─ 3. Validación cara (ya con bytes en disco)
   │     MIME real vía Tika, defensa contra zip bomb
   │
   ├─ 4. ¿Existe (owner_id, sha256)? ──► sí: devuelve el upload existente, 200
   │
   ├─ 5. INSERT uploads (status=RECEIVED)
   ├─ 6. Publica evento ─────────────────────────────────► 202 {id, status}
   │
   └── (asíncrono) ─────────────────────────────────────────────────┐
                                                                    │
   status=PARSING, started_at, attempts++, heartbeat cada 5 s       │
   Pase 1: inferencia de esquema (lee todo, no escribe)             │
   Pase 2: normaliza y escribe filas en lotes de 1000               │
   INSERT datasets + dataset_columns                                │
   status=READY, finished_at, row_count                             │
                                                                    │
   cualquier error ──► status=FAILED, error_code, error_detail ─────┘
```

Mientras tanto, el frontend hace polling de `GET /api/uploads/{id}` cada 2 s con
retroceso exponencial hasta 10 s, y corta a los 15 minutos.

## Estados

| Estado | Significa | Transiciones válidas |
|---|---|---|
| `RECEIVED` | Bytes en disco, fila creada, nadie lo ha tomado | → `PARSING`, → `FAILED` |
| `PARSING` | Un trabajador lo tiene, `heartbeat_at` se actualiza | → `READY`, → `FAILED` |
| `READY` | Existe un `dataset` consultable | terminal |
| `FAILED` | `error_code` explica por qué; se puede reintentar | → `PARSING` (reintento manual) |

Las transiciones se validan en el dominio. Un `UPDATE` que lleve de `READY` a
`PARSING` sin pasar por el reintento explícito debe lanzar excepción, no
ejecutarse.

## Recuperación

Aquí es donde `@Async` a secas se queda corto: si el contenedor muere durante un
parseo, esa fila queda en `PARSING` para siempre y el frontend hace polling
eterno.

Dos mecanismos:

1. **Reaper al arrancar.** Al levantar la aplicación, todo upload en `PARSING`
   cuyo `heartbeat_at` tenga más de 2 minutos pasa a `FAILED` con
   `error_code=INTERRUPTED`. Es la limpieza tras un reinicio.
2. **Barrido periódico.** Un `@Scheduled` cada minuto aplica la misma regla, para
   el caso de un hilo colgado sin que el proceso muriera.

`attempts` limita a 3 reintentos. Al cuarto, `FAILED` es definitivo y requiere
intervención.

Este diseño es lo que hace barato cambiar `@Async` por Rabbit después: el
disparador cambia, la máquina de estados no. Ver Decisión 005.

## Validaciones y límites

Un CSV de 2 GB no es un archivo, es una negación de servicio. Los límites se
aplican en tres capas, porque cada una atrapa lo que la anterior no puede:

| Capa | Límite | Qué atrapa |
|---|---|---|
| Caddy (`edge`) | `max_size 25MB` en el cuerpo | Corta la conexión sin gastar la JVM |
| Spring | `spring.servlet.multipart.max-file-size: 25MB` | Defensa si alguien pega directo al `api` |
| Parser | 200 columnas, 500 000 filas, 1 MB por celda | Archivos pequeños que se expanden al leerse |

Más:

- **MIME real, no extensión.** Tika sobre los primeros bytes. Aceptados:
  `text/csv`, `text/plain`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.
  Un `.csv` que resulta ser un ejecutable se rechaza con `UNSUPPORTED_TYPE`.
- **`.xls` (formato viejo, binario) no se acepta.** Solo `.xlsx`. Soportar el
  binario duplica superficie de ataque por un formato en retirada.
- **Zip bomb.** `.xlsx` es un ZIP. `ZipSecureFile.setMinInflateRatio(0.01)` y
  tope de tamaño descomprimido; POI lo detecta y aborta.
- **POI en modo evento, nunca `WorkbookFactory.create()`.** Cargar el workbook
  completo en memoria convierte un archivo de 20 MB en cientos de MB de heap.
- **Solo la primera hoja** del `.xlsx` en la versión inicial, y el nombre se
  guarda en `datasets.sheet_name`. Selección de hoja es una mejora posterior,
  no un pendiente oculto.

## Inferencia de esquema

Es la parte que parece trivial en la lista de tareas y consume la mitad del
tiempo real. Por eso lleva especificación antes que código.

### Dos pases

**Pase 1 — observar.** Lee el archivo completo sin escribir nada. Por columna
acumula: cuántos nulos, cuántos valores encajan en cada tipo candidato, hasta
1000 valores distintos de muestra, mínimo y máximo.

**Pase 2 — normalizar y escribir.** Con el tipo ya decidido, convierte cada
valor y escribe en lotes de 1000 filas.

Se lee el archivo dos veces a propósito. La alternativa —decidir con una muestra
de las primeras N filas— falla exactamente en el caso que importa: la columna
que parece entera durante 900 filas y trae un decimal en la 5000.

### Detección del delimitador (CSV)

Se prueban `,`, `;`, `\t` y `|` sobre las primeras 20 líneas. Gana el que
produzca el mismo número de campos en todas ellas y ese número sea mayor a 1.
Si empatan, gana `,`. Si ninguno cumple, `MALFORMED_CSV`.

También se detecta y se descarta el BOM UTF-8, que si no se convierte en parte
del nombre de la primera columna y nadie entiende por qué.

### Encabezado

Se asume encabezado si la primera fila es 100 % texto no numérico y ninguna
celda está vacía. Si no, las columnas se llaman `columna_1`, `columna_2`, …
y `has_header = false`.

### Orden de resolución de tipos

Por columna, sobre los valores **no nulos**. Un tipo gana solo si el **100 %**
de los valores no nulos encaja:

1. `BOOLEAN` — `true/false`, `1/0`, `sí/no`, `si/no`, `yes/no`, `verdadero/falso`
   (sin distinguir mayúsculas)
2. `INTEGER` — opcional signo, dígitos, opcional separador de miles consistente
3. `DECIMAL` — ver regla del separador abajo
4. `DATE` / `TIMESTAMP` — ver formatos abajo
5. `STRING` — el que siempre gana si ningún otro lo hace

Una columna 100 % vacía es `STRING` con `null_count = row_count`.

### La regla del separador decimal

El caso que rompe a todo mundo: `1,5` puede ser mil quinientos o uno punto cinco.

- Si la columna contiene algún valor con `.` como separador decimal
  (`\d+\.\d+`), entonces `,` es separador de miles.
- Si **ningún** valor usa `.` y hay valores tipo `\d+,\d{1,2}` de forma
  consistente, `,` es el separador decimal.
- Si hay valores de ambas formas mezclados en la misma columna → `STRING` y
  `type_ambiguous = true`. Adivinar aquí corrompe datos en silencio, que es peor
  que no tipar.

La decisión es **por columna**, nunca global al archivo.

### Fechas

Formatos probados, en orden: `yyyy-MM-dd`, `yyyy-MM-dd'T'HH:mm:ss[X]`,
`dd/MM/yyyy`, `MM/dd/yyyy`, `dd-MM-yyyy`, `yyyy/MM/dd`.

Gana el primero que parsee el 100 % de los valores no nulos. Si `dd/MM/yyyy` y
`MM/dd/yyyy` parsean ambos todo (es decir, ningún valor tiene día > 12 que
desambigüe), se elige `dd/MM/yyyy` por convención local **y se marca
`type_ambiguous = true`**, que la UI muestra como advertencia.

Nunca se infiere zona horaria. Sin zona explícita, se guarda como fecha local.

### Cuando un valor no convierte en el pase 2

No debería pasar —el tipo se eligió con el 100 % de los valores— pero puede
ocurrir con archivos que cambian entre pases. Si pasa: el valor se guarda como
cadena en el JSON, se registra en `error_detail`, y el parseo **continúa**. Un
archivo entero perdido por una celda es peor comportamiento que un dataset con
una nota.

## Catálogo de errores

`error_code` es un enum, no texto libre. El frontend traduce el código; el
`error_detail` es para el humano que depura.

| Código | Cuándo |
|---|---|
| `FILE_TOO_LARGE` | Excede 25 MB |
| `UNSUPPORTED_TYPE` | MIME real no aceptado |
| `MALFORMED_CSV` | No se pudo determinar delimitador o estructura inconsistente |
| `TOO_MANY_COLUMNS` | Más de 200 |
| `TOO_MANY_ROWS` | Más de 500 000 |
| `EMPTY_FILE` | Cero filas de datos |
| `DUPLICATE_HEADERS` | Encabezados que colisionan tras normalizar (se resuelve con sufijo; error solo si es irrecuperable) |
| `ZIP_BOMB` | Ratio de inflado sospechoso en `.xlsx` |
| `INTERRUPTED` | El proceso murió durante el parseo (lo pone el reaper) |
| `INTERNAL` | Cualquier otra cosa; siempre con `traceId` en el log |

## Almacenamiento del archivo crudo

Detrás de una interfaz:

```java
public interface StorageService {
    StoredFile store(InputStream in, String suggestedName);  // devuelve key + sha256 + size
    InputStream open(String key);
    void delete(String key);
}
```

Implementación inicial: `LocalStorageService`, escribiendo bajo un volumen de
Docker con el layout `{ownerId}/{yyyy}/{MM}/{uuid}{ext}`. Mover a MinIO o S3 es
implementar la interfaz, sin tocar el dominio. Ver Decisión 009.

El crudo **se conserva** después de parsear: es lo que permite reprocesar cuando
mejoren las reglas de inferencia, sin pedirle al usuario que vuelva a subir.
