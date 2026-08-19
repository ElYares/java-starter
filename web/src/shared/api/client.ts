import axios from 'axios'

import { ApiError } from './ApiError'

// Cuanto se espera antes de dar el servidor por perdido.
//
// Sin tope, un backend colgado deja la SPA en "cargando" para siempre y la
// vista de disponibilidad de CU-003 no aparece nunca: un servidor que no
// contesta es indistinguible de uno lento. Las peticiones que legitimamente
// tardan mas —la subida de archivos de la fase 2 del roadmap— lo suben en su
// propia llamada, no aqui.
const ESPERA_MAXIMA_MS = 15_000

/**
 * La unica instancia de Axios de la aplicacion. Nunca un `fetch` suelto en un
 * componente.
 *
 * Todo viaja al mismo origen gracias al edge, y de ahi salen dos propiedades
 * que no son configuracion sino la Decision 003 hecha codigo: el navegador
 * manda las cookies `HttpOnly` solo, y Axios encuentra la cookie de CSRF porque
 * es del mismo sitio. Separar los origenes rompe las dos a la vez.
 */
export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  // Los nombres que emite Spring Security con
  // `CookieCsrfTokenRepository.withHttpOnlyFalse()`. Son los mismos que Axios
  // trae por omision; van explicitos porque el que manda es el backend.
  //
  // `withXSRFToken` se deja sin tocar a proposito: sin el, Axios adjunta el
  // header solo si la URL es del mismo origen. Ponerlo en `true` mandaria el
  // token tambien a un tercero el dia que alguien apunte el `baseURL` a otro
  // lado, y esa falla es peor que quedarse sin CSRF.
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  timeout: ESPERA_MAXIMA_MS,
})

// Primera responsabilidad del interceptor de respuesta: normalizar.
//
// Aqui el error deja de ser "lo que sea que Axios haya rechazado" y pasa a ser
// siempre un `ApiError`. Es lo que permite que una vista escriba
// `catch (e) { if (e.unavailable) ... }` sin comprobar antes de que tipo es.
//
// Las otras dos —el refresh ante un 401 y el reintento— se montan sobre este en
// la fase 3. El orden importa: van despues, para que el reintento decida sobre
// un error ya normalizado.
api.interceptors.response.use(
  (respuesta) => respuesta,
  (error: unknown) => Promise.reject(ApiError.from(error)),
)
