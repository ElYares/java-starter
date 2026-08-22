import type { ApiError as CuerpoDeErrorGenerado } from './generated'

/**
 * La cara del contrato que usa la aplicacion.
 *
 * Es la unica puerta a `generated/`. Existe por dos razones concretas, y
 * ninguna es estetica:
 *
 * 1. **Colision de nombres.** El esquema del backend se llama `ApiError` y en
 *    esta misma carpeta vive la clase `ApiError`, que es otra cosa: la del
 *    backend es el cuerpo que viaja por el cable, la del frontend es el error
 *    normalizado que ven las vistas y que tambien existe cuando *no hubo*
 *    respuesta. Importar las dos con el mismo nombre en el mismo archivo es
 *    como se escribe un bug que el compilador no puede ver.
 * 2. **Un solo punto de cambio.** El generador decide los nombres, y los cambia
 *    entre versiones — `MeResponse2` de ahi al lado es prueba de ello. Con este
 *    archivo en medio, una version nueva del generador toca un solo import.
 *
 * Nada de `generated/` se edita a mano. Se regenera con `npm run api:types`.
 */

export type { FieldIssue, LoginRequest, MeResponse } from './generated'

/** El cuerpo de error tal como lo declara el backend, en el cable. */
export type CuerpoDeError = CuerpoDeErrorGenerado

/**
 * El catalogo cerrado de codigos del servidor, sacado del enum `ErrorCode`.
 *
 * Es una union de literales, no `string`: un codigo mal escrito en un `switch`
 * lo detecta `vue-tsc`, y el dia que el backend retire un codigo, todo sitio
 * que lo nombre deja de compilar. Ese es el punto entero de HU-004.
 */
export type CodigoDelServidor = CuerpoDeErrorGenerado['code']
