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
    api/             client.ts, generated/ (cliente TS desde OpenAPI)
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

Un interceptor de respuesta con tres responsabilidades:

1. **Normalizar el error.** Todo error se convierte en un `ApiError` con `code`,
   `status`, `message`, `fieldErrors` y `traceId`, leyendo el `problem+json` del
   backend. Las vistas nunca inspeccionan `error.response.data.detail`.
2. **Refresh en 401, una sola vez.** Al recibir `401`, se llama a
   `POST /api/auth/refresh`. Si funciona, se reintenta la petición original; si
   falla, se limpia el store y se redirige a login. **Con una promesa compartida**:
   si cinco peticiones fallan a la vez, se hace un solo refresh y las cinco
   esperan a esa promesa. Sin eso, cinco refresh concurrentes rotan el token
   cinco veces y cuatro se invalidan mutuamente.
   El `refresh` mismo queda excluido del interceptor, o el bucle es infinito.
3. **Adjuntar el `traceId` al error visible.** Cuando el usuario reporte algo,
   ese código es lo que lo conecta con el log del servidor.

**El cliente tipado se genera** desde `/api/openapi.json` hacia
`shared/api/generated/`. Es artefacto de build, no se edita ni se revisa a mano.
El día que el backend renombre un campo, el error aparece al compilar, no en
producción.

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
