# Frontend

Vue 3 con Composition API, Vite, Pinia, Vue Router y TypeScript. Lo que separa
un starter mediocre de uno bueno no es la lista de librerías, son las cuatro
cosas de abajo.

## Estructura

```
web/src/
  main.ts
  app/
    router/          rutas, guards, meta
    layouts/         AuthLayout.vue, AppLayout.vue
    stores/          auth.ts (Pinia)
  shared/
    api/             client.ts, contrato.ts, generated/ (tipos desde OpenAPI)
    ui/              componentes tontos y reutilizables
    composables/     useAsyncResource, usePolling
  features/
    auth/            LoginView, useLogin
    uploads/         UploadView, UploadListView, useUploadPolling
    datasets/        DatasetTableView, useDatasetRows
    charts/          ChartBuilderView, useChartQuery
    users/           UserListView, UserFormView
```

Misma idea que el backend: **el primer nivel es el negocio**. Un `components/`
con cuarenta archivos planos no dice de qué trata la aplicación.

`shared/` es para lo que usan dos o más features. Nada nace ahí; se promueve
cuando aparece el segundo consumidor.

## Capa de API

Una sola instancia de Axios. **Nunca un `fetch` suelto en un componente.**

```ts
// shared/api/client.ts
export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,     // las cookies HttpOnly viajan solas
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})
```

**Dos** interceptores de respuesta, y el orden entre ellos es contrato:

1. **Refresh en 401, una sola vez** (`shared/api/refresh.ts`). Al recibir `401`
   se llama a `POST /api/auth/refresh` y se reintenta la petición original.
   **Con una promesa compartida**: si cinco peticiones fallan a la vez se hace un
   solo refresh y las cinco esperan a esa promesa. Sin eso, cinco refresh
   concurrentes rotan el token cinco veces y cuatro se invalidan mutuamente.
   `/auth/refresh` queda excluido, o el bucle es infinito; `/auth/login` también,
   porque ahí un `401` son credenciales malas. Y solo se intenta si está la cookie
   `has_session` — ver Decisión 014 y `docs/04-contratos-api.md`.
2. **Normalizar el error** (`shared/api/client.ts`). Todo error se convierte en un
   `ApiError` con `code`, `status`, `message`, `fieldErrors` y `traceId`, leyendo
   el `problem+json` del backend. Las vistas nunca inspeccionan
   `error.response.data.detail`. El `traceId` viaja ahí: cuando el usuario reporte
   algo, ese código es lo que lo conecta con el log del servidor.

**Va primero el refresh y no la normalización.** Reintentar exige el `config` de
la petición original, y eso solo lo trae el error de Axios: un `ApiError` no lo
lleva ni debe llevarlo, porque es el tipo que ven las vistas y un config de
transporte no tiene nada que hacer ahí. De paso, un reintento que funciona no
produce ningún error, así que el normalizador ni se entera.

El interceptor **no importa el store ni el router** — `shared/` no puede depender
de `app/`. Expone `onSessionLost(cb)` y el router lo conecta.

`ApiError` distingue **"el servidor dijo que no" de "no hubo servidor"**
(`answered`) y de "el backend está caído" (`unavailable`, que incluye `502`/`503`/
`504`). Confundir no disponible con no autenticado hace que una caída de treinta
segundos expulse a todos los usuarios de su sesión.

### Los tipos vienen del contrato

```
npm run api:types      # regenera shared/api/generated/ desde /api/openapi.json
```

Los tipos de `shared/api` ya no se escriben a mano: los genera
`@hey-api/openapi-ts` a partir del contrato que publica el backend. Nada de
`generated/` se edita — se regenera.

**Solo tipos, sin SDK ni cliente.** El transporte ya existe y no es negociable:
`client.ts` es la instancia de Axios con la cadena de interceptores de la que
depende CU-003 entero. Un cliente generado traería su propia instancia sin nada
de eso, y cambiaría una duplicación de tipos por una duplicación de transporte,
que es peor.

**`contrato.ts` es la única puerta a `generated/`.** Existe por dos razones: el
esquema del backend se llama `ApiError` y en esta misma carpeta vive la clase
`ApiError`, que es otra cosa —el cuerpo del cable contra el error normalizado que
ven las vistas, que también existe cuando no hubo respuesta—; y porque el
generador decide los nombres y los cambia entre versiones, así que conviene que
eso toque un solo archivo.

De ahí salen dos tipos que hacen trabajo real:

- **`MeResponse`** reemplazó a la interfaz `Perfil` escrita a mano. Comprobado:
  renombrar `displayName` en el DTO del backend, regenerar y correr `vue-tsc`
  falla en `AppLayout.vue(28,53)` y `DashboardView.vue(12,48)` — el punto de uso,
  con archivo y línea. Antes ese rename llegaba a producción en silencio.
- **`CodigoDelServidor`** es el enum `ErrorCode` como unión de literales, y con
  él está tipada la tabla `CODIGO_POR_ESTADO` de `ApiError.ts`. El día que el
  backend retire un código, esa tabla deja de compilar.

**`generated/` se versiona en git**, no se regenera en CI. Dos razones: el cambio
de contrato aparece en el diff del PR, que es justo cuando uno quiere verlo; y el
job de frontend sigue corriendo con solo Node, sin levantar el backend. La
generación no puede ser un paso obligatorio de `npm run build` por lo mismo — en
una máquina sin Docker fallaría.

El precio es que nadie comprueba automáticamente que lo versionado esté al día
con el backend. Hoy la garantía es humana: se regenera y `vue-tsc` avisa.

## Layouts

Dos, desde el principio:

- `AuthLayout` — centrado, sin navegación. Login y recuperación.
- `AppLayout` — sidebar, encabezado con el usuario, área de contenido.

Se asignan en la ruta, no dentro de la vista:

```ts
{ path: '/login', component: LoginView, meta: { layout: 'auth', public: true } }
```

Meter el sidebar como componente dentro de cada vista es el anti-patrón que se
paga cuando hay quince vistas y hay que cambiar el menú.

## Guards por meta

Genéricos desde el inicio, aunque solo haya dos roles:

```ts
{
  path: '/users',
  component: UserListView,
  meta: { requiresAuth: true, roles: ['ADMIN'] }
}
```

Un solo `beforeEach` lee `meta` y decide. Nunca un `if (user.role === 'ADMIN')`
dentro de un componente: eso reparte la regla de autorización por todo el
código.

Dos detalles:

- **El guard rehidrata la sesión al primer arranque.** Con cookies HttpOnly el
  frontend no sabe si hay sesión hasta preguntar: en la primera navegación llama
  a `GET /api/auth/me` y espera esa respuesta antes de decidir. Sin esto, un F5
  en una ruta privada rebota a login teniendo sesión válida.
- **El guard es conveniencia, no seguridad.** La autorización real está en el
  servidor. Ocultar un botón no protege un endpoint.

## Los cuatro estados

El hábito más subestimado. **Toda vista que consuma datos tiene cuatro estados,
no dos**: cargando, vacío, error, con datos.

Un composable que los impone:

```ts
const { data, loading, error, isEmpty, reload } = useAsyncResource(
  () => api.get('/uploads', { params: { page, size } })
)
```

Y un componente que los renderiza:

```vue
<AsyncSection :loading="loading" :error="error" :empty="isEmpty" @retry="reload">
  <UploadTable :rows="data.content" />
  <template #empty>Todavía no has subido ningún archivo.</template>
</AsyncSection>
```

Reglas:

- El estado vacío **dice qué hacer**, no "sin datos". Es la primera pantalla que
  ve un usuario nuevo y suele ser la única oportunidad de explicarle el producto.
- El estado de error muestra el mensaje traducido del `code`, un botón de
  reintentar, y el `traceId` en letra chica.
- Cargando es un esqueleto de la forma final, no un spinner centrado que salta
  el layout al llegar los datos.

## Polling del upload

Vive en un composable, no en la vista:

```ts
const { status, datasetId, stop } = useUploadPolling(uploadId)
```

- Intervalo 2 s, con retroceso exponencial hasta 10 s.
- Se detiene en `READY` o `FAILED`, al desmontar el componente, y a los 15
  minutos duros.
- Se pausa cuando la pestaña está oculta (`visibilitychange`). Una pestaña
  olvidada en segundo plano no debe estar golpeando el API toda la tarde.

## Subida de archivos

- Validación en el cliente de extensión y tamaño **antes** de enviar: no protege
  nada (el servidor revalida), pero evita que el usuario espere una subida de
  25 MB para recibir un rechazo.
- Barra de progreso con `onUploadProgress` de Axios.
- Al llegar el `202`, se navega al detalle del upload y arranca el polling. El
  usuario nunca se queda mirando un botón deshabilitado sin saber qué pasa.

## Tabla del dataset

- **Paginación del servidor, siempre.** Nunca se traen todas las filas para
  paginar en memoria.
- Las columnas se construyen desde `dataset.columns`, con la alineación y el
  formato dictados por `inferredType` (números a la derecha, fechas formateadas,
  booleanos como ícono).
- Las columnas con `typeAmbiguous` llevan una marca discreta con explicación al
  pasar el cursor. El usuario tiene que poder enterarse de que el sistema
  adivinó.
- Ordenar y filtrar manda parámetros al servidor. Ordenar en el cliente miente:
  ordena la página, no el dataset.

## Gráficas

ECharts. Lo interesante no es la librería sino **el selector**, que se arma solo
a partir del esquema:

- La lista de dimensiones ofrece columnas `STRING`, `DATE`, `BOOLEAN`.
- La lista de medidas ofrece columnas `INTEGER` y `DECIMAL`.
- Las agregaciones disponibles dependen del tipo elegido. `SUM` no aparece si la
  medida no es numérica — deshabilitar es mejor que devolver `422`.
- El tipo de gráfica se sugiere según la combinación (dimensión temporal →
  líneas; categórica con pocos grupos → barras), pero se puede cambiar.

La vista llama a `POST /api/datasets/{id}/query` y dibuja los puntos que le
regresan. **El frontend no agrega nada.** Si hace falta un cálculo nuevo, se
agrega al endpoint.

Cuando la respuesta trae `truncated: true`, la gráfica lo dice explícitamente:
"mostrando los 50 grupos con mayor valor de 312".
