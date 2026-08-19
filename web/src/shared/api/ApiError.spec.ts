import { AxiosError, AxiosHeaders, CanceledError } from 'axios'
import { describe, expect, it } from 'vitest'

import { ApiError, CANCELED, CLIENT, NETWORK, TIMEOUT } from './ApiError'

// Un error de Axios con respuesta del servidor. Se construye a mano en vez de
// levantar una peticion real porque lo que se prueba es la traduccion, no el
// transporte.
function conRespuesta(status: number, data: unknown): AxiosError {
  const error = new AxiosError('Request failed', AxiosError.ERR_BAD_REQUEST)
  error.response = {
    data,
    status,
    statusText: '',
    headers: {},
    config: { headers: new AxiosHeaders() },
  }

  return error
}

/** Sin respuesta: la peticion salio y no volvio nada. */
function sinRespuesta(code: string): AxiosError {
  return new AxiosError('Network Error', code)
}

describe('ApiError', () => {
  it('traduce el problem+json de una validacion', () => {
    const error = ApiError.from(
      conRespuesta(400, {
        type: '/errors/validation-failed',
        title: 'La peticion no es valida',
        status: 400,
        detail: 'Revisa los campos marcados',
        code: 'VALIDATION_FAILED',
        traceId: '8f2c1a',
        errors: [
          { field: 'email', code: 'NotBlank', message: 'El email es obligatorio' },
          // Dos restricciones sobre el mismo campo: `AppField` muestra una.
          { field: 'email', code: 'Email', message: 'El email no tiene formato' },
        ],
      }),
    )

    expect(error.code).toBe('VALIDATION_FAILED')
    expect(error.status).toBe(400)
    expect(error.message).toBe('Revisa los campos marcados')
    expect(error.fieldErrors).toEqual({ email: 'El email es obligatorio' })
    expect(error.traceId).toBe('8f2c1a')
  })

  // Las dos pruebas que sostienen E2 de CU-003. Si estas dos afirman lo mismo,
  // el guard no puede distinguir una sesion vencida de un backend caido y una
  // caida corta expulsa a todos los usuarios.
  it('un rechazo del servidor no es una caida', () => {
    const error = ApiError.from(
      conRespuesta(401, { code: 'UNAUTHENTICATED', detail: 'No has iniciado sesion' }),
    )

    expect(error.code).toBe('UNAUTHENTICATED')
    expect(error.answered).toBe(true)
    expect(error.unavailable).toBe(false)
  })

  it('la falta de respuesta si lo es', () => {
    const error = ApiError.from(sinRespuesta(AxiosError.ERR_NETWORK))

    expect(error.code).toBe(NETWORK)
    expect(error.status).toBe(0)
    expect(error.answered).toBe(false)
    expect(error.unavailable).toBe(true)
  })

  it('cuenta el tiempo agotado como falta de respuesta', () => {
    expect(ApiError.from(sinRespuesta(AxiosError.ECONNABORTED)).code).toBe(TIMEOUT)
    // El mismo caso con `clarifyTimeoutError` activo.
    expect(ApiError.from(sinRespuesta(AxiosError.ETIMEDOUT)).unavailable).toBe(true)
  })

  // Una cancelacion tampoco tiene respuesta, pero no es evidencia de nada: la
  // provoca la aplicacion misma al abandonar una peticion en curso.
  it('no confunde una cancelacion con una caida', () => {
    const error = ApiError.from(new CanceledError('canceled'))

    expect(error.code).toBe(CANCELED)
    expect(error.answered).toBe(false)
    expect(error.unavailable).toBe(false)
  })

  // El edge contesta esto cuando el contenedor del API no esta: es una
  // respuesta HTTP de verdad, con cuerpo HTML y sin nada del molde de HU-002.
  it('reconoce la caida detras de un 502 sin problem+json', () => {
    const error = ApiError.from(conRespuesta(502, '<html>502 Bad Gateway</html>'))

    expect(error.code).toBe('INTERNAL')
    expect(error.traceId).toBeNull()
    expect(error.fieldErrors).toEqual({})
    expect(error.answered).toBe(true)
    expect(error.unavailable).toBe(true)
  })

  // Un bug del navegador no debe mandar a nadie a revisar el servidor.
  it('no culpa al backend de un error que no es de la red', () => {
    const error = ApiError.from(new TypeError('x is not a function'))

    expect(error.code).toBe(CLIENT)
    expect(error.unavailable).toBe(false)
    expect(error.cause).toBeInstanceOf(TypeError)
  })

  it('es idempotente', () => {
    const original = ApiError.from(sinRespuesta(AxiosError.ERR_NETWORK))

    expect(ApiError.from(original)).toBe(original)
  })
})
