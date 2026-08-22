import axios from 'axios'

import type { CodigoDelServidor } from './contrato'

// Codigos que nacen en el cliente y no en el servidor.
//
// El catalogo de `ErrorCode` del backend solo describe lo que ocurre cuando
// hubo una respuesta. Estos cuatro describen el caso contrario y por
// construccion no chocan con el, porque el servidor no los emite.
export const NETWORK = 'NETWORK'
export const TIMEOUT = 'TIMEOUT'
export const CANCELED = 'CANCELED'
export const CLIENT = 'CLIENT'

// Espejo de `ErrorCode.forStatus` del backend, para las respuestas de error que
// llegan sin `code`: un 502 del edge con cuerpo HTML, o cualquier intermediario
// que conteste antes de que la peticion llegue a Spring. Sin esto esas
// respuestas llegarian a las vistas sin eje de decision, que es exactamente lo
// que HU-002 vino a evitar.
//
// Sigue siendo un espejo, pero ya no uno que nadie vigila: los valores estan
// tipados contra el catalogo que publica el contrato, asi que el dia que el
// backend retire un codigo esta tabla deja de compilar.
const CODIGO_POR_ESTADO: Record<number, CodigoDelServidor> = {
  400: 'BAD_REQUEST',
  401: 'UNAUTHENTICATED',
  403: 'FORBIDDEN',
  404: 'NOT_FOUND',
  405: 'METHOD_NOT_ALLOWED',
  406: 'NOT_ACCEPTABLE',
  409: 'CONFLICT',
  413: 'FILE_TOO_LARGE',
  415: 'UNSUPPORTED_TYPE',
  422: 'UNPROCESSABLE',
  429: 'TOO_MANY_REQUESTS',
  500: 'INTERNAL',
}

// El servidor contesto, pero lo que contesto es que no hay servicio detras. Son
// los estados que el edge produce solo cuando el contenedor del API no esta:
// para el usuario es una caida, no un rechazo.
const SIN_SERVICIO = new Set([502, 503, 504])

/**
 * El unico tipo de error que ven las vistas.
 *
 * Existe para que ninguna vista lea `error.response.data.detail`. Ese acceso
 * repetido treinta veces es lo que hace que renombrar un campo del backend se
 * descubra en produccion, y ademas rompe con un `undefined` cuando el error no
 * trajo respuesta.
 *
 * La distincion que sostiene CU-003 es {@link answered}: "el servidor dijo que
 * no" y "no hubo servidor" son dos cosas distintas, y confundirlas hace que una
 * caida de treinta segundos expulse a todos los usuarios de su sesion.
 */
export class ApiError extends Error {
  /** Eje de decision. Del catalogo del backend, o uno de los cuatro de arriba. */
  readonly code: string

  /** Estado HTTP, o `0` cuando no hubo respuesta que leer. */
  readonly status: number

  /** Campo a mensaje, listo para el prop `error` de `AppField`. */
  readonly fieldErrors: Readonly<Record<string, string>>

  /** Lo que conecta un "no funciona" con el log del servidor. */
  readonly traceId: string | null

  /**
   * Hubo una respuesta HTTP.
   *
   * Es el hecho crudo, no la conclusion: un `401` la tiene en `true` y un
   * `502` tambien. Para decidir si el backend esta caido usa
   * {@link unavailable}.
   */
  readonly answered: boolean

  private constructor(campos: {
    code: string
    status: number
    message: string
    fieldErrors?: Record<string, string>
    traceId?: string | null
    answered: boolean
    cause?: unknown
  }) {
    super(campos.message, { cause: campos.cause })
    this.name = 'ApiError'
    this.code = campos.code
    this.status = campos.status
    this.fieldErrors = campos.fieldErrors ?? {}
    this.traceId = campos.traceId ?? null
    this.answered = campos.answered
  }

  /**
   * El backend no esta disponible.
   *
   * Definido en positivo a proposito: solo la falta de respuesta por red o por
   * tiempo agotado, y los estados que delatan que no hay servicio detras del
   * edge. Una cancelacion tambien se queda sin respuesta y no es evidencia de
   * nada, y un error del propio navegador tampoco.
   *
   * Es la condicion de E2 de CU-003: con esto en `true` el guard muestra la
   * vista de disponibilidad, nunca la de login.
   */
  get unavailable(): boolean {
    return this.answered
      ? SIN_SERVICIO.has(this.status)
      : this.code === NETWORK || this.code === TIMEOUT
  }

  /**
   * Normaliza cualquier cosa que se pueda atrapar.
   *
   * Es idempotente: reprocesar un `ApiError` lo devuelve tal cual, para que un
   * interceptor encadenado sobre otro no pierda el error original envolviendolo
   * dos veces.
   */
  static from(error: unknown): ApiError {
    if (error instanceof ApiError) {
      return error
    }

    if (axios.isCancel(error)) {
      return new ApiError({
        code: CANCELED,
        status: 0,
        message: 'La peticion se cancelo.',
        answered: false,
        cause: error,
      })
    }

    if (axios.isAxiosError(error)) {
      return error.response
        ? ApiError.desdeRespuesta(error.response.status, error.response.data, error)
        : ApiError.sinRespuesta(error.code, error)
    }

    // Ni respuesta ni peticion: revento algo del lado del navegador, como un
    // interceptor con un bug. Tiene su propio codigo porque reportarlo como
    // caida del backend manda a diagnosticar al lugar equivocado.
    return new ApiError({
      code: CLIENT,
      status: 0,
      message: 'Ocurrio un error inesperado en la aplicacion.',
      answered: false,
      cause: error,
    })
  }

  private static desdeRespuesta(status: number, cuerpo: unknown, cause: unknown): ApiError {
    const problema = esObjeto(cuerpo) ? cuerpo : {}

    return new ApiError({
      code: texto(problema.code) ?? codigoPorEstado(status),
      status,
      // `detail` es el mensaje del caso concreto y `title` el generico del
      // codigo. Ninguno se usa para decidir, solo para mostrar.
      message:
        texto(problema.detail) ?? texto(problema.title) ?? 'El servidor rechazo la peticion.',
      fieldErrors: leerFieldErrors(problema.errors),
      traceId: texto(problema.traceId) ?? null,
      answered: true,
      cause,
    })
  }

  private static sinRespuesta(codigoDeAxios: string | undefined, cause: unknown): ApiError {
    // `ECONNABORTED` es el codigo por omision del tiempo agotado; `ETIMEDOUT` es
    // el mismo caso con `transitional.clarifyTimeoutError` activo. Se aceptan los
    // dos para que este modulo no dependa de como este configurada la instancia.
    const porTiempo = codigoDeAxios === 'ECONNABORTED' || codigoDeAxios === 'ETIMEDOUT'

    return new ApiError({
      code: porTiempo ? TIMEOUT : NETWORK,
      status: 0,
      message: porTiempo
        ? 'El servidor tardo demasiado en responder.'
        : 'No se pudo contactar al servidor.',
      answered: false,
      cause,
    })
  }
}

function codigoPorEstado(status: number): CodigoDelServidor {
  return CODIGO_POR_ESTADO[status] ?? (status >= 400 && status < 500 ? 'BAD_REQUEST' : 'INTERNAL')
}

function leerFieldErrors(errors: unknown): Record<string, string> {
  if (!Array.isArray(errors)) {
    return {}
  }

  const porCampo: Record<string, string> = {}

  for (const item of errors) {
    if (!esObjeto(item)) {
      continue
    }

    const field = texto(item.field)
    const message = texto(item.message)

    // Bean Validation puede reportar dos restricciones sobre el mismo campo y
    // `AppField` muestra un solo mensaje: gana el primero, que es el orden en
    // que el backend los enumera.
    if (field && message && !Object.hasOwn(porCampo, field)) {
      porCampo[field] = message
    }
  }

  return porCampo
}

function esObjeto(valor: unknown): valor is Record<string, unknown> {
  return typeof valor === 'object' && valor !== null
}

function texto(valor: unknown): string | undefined {
  return typeof valor === 'string' && valor.length > 0 ? valor : undefined
}
