import { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'

import { ApiError, NETWORK } from './ApiError'
import { api } from './client'

/**
 * Sustituye el transporte para que la peticion no salga de la prueba.
 *
 * Corta por debajo de los interceptores, asi que lo que se ejerce es la cadena
 * real del cliente. Lo que no se ejerce es lo que Axios hace dentro del
 * adaptador: ahi vive el header de CSRF, y por eso la prueba de abajo afirma su
 * ausencia y no su presencia.
 */
function transporte(responder: (config: InternalAxiosRequestConfig) => unknown): AxiosAdapter {
  return async (config) => {
    const resultado = responder(config)

    if (resultado instanceof Error) {
      throw resultado
    }

    return {
      data: resultado,
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }
  }
}

describe('api', () => {
  // Las cuatro juntas y no dos, porque son las cuatro que HU-004 fija como
  // contrato del cliente. El `baseURL` es la otra mitad del contrato OpenAPI:
  // el documento declara `servers: ['/api']` y las rutas sin ese prefijo, asi
  // que si esta instancia dejara de anteponerlo, cada llamada del cliente
  // generado pediria una ruta que no existe.
  it('apunta al mismo origen, deja viajar las cookies y nombra el CSRF como Spring', () => {
    expect(api.defaults.baseURL).toBe('/api')
    expect(api.defaults.withCredentials).toBe(true)
    expect(api.defaults.xsrfCookieName).toBe('XSRF-TOKEN')
    expect(api.defaults.xsrfHeaderName).toBe('X-XSRF-TOKEN')
  })

  it('entrega un ApiError cuando el servidor rechaza', async () => {
    const rechazo = new AxiosError('Request failed', AxiosError.ERR_BAD_REQUEST)
    rechazo.response = {
      data: { code: 'UNAUTHENTICATED', detail: 'No has iniciado sesion', traceId: 'abc123' },
      status: 401,
      statusText: '',
      headers: {},
      config: { headers: new AxiosHeaders() },
    }

    const error = await api.get('/auth/me', { adapter: transporte(() => rechazo) }).catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('UNAUTHENTICATED')
    expect(error.traceId).toBe('abc123')
    expect(error.unavailable).toBe(false)
  })

  it('entrega un ApiError cuando no hay servidor', async () => {
    const caida = new AxiosError('Network Error', AxiosError.ERR_NETWORK)

    const error = await api.get('/auth/me', { adapter: transporte(() => caida) }).catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe(NETWORK)
    expect(error.unavailable).toBe(true)
  })

  // El header de CSRF lo pone Axios dentro del adaptador, leyendo la cookie en
  // el momento de cada peticion. Si aparece antes, es que alguien lo escribio a
  // mano, y con eso vuelve el bug: Spring rota `XSRF-TOKEN` en cada refresh
  // autenticado, asi que un token capturado una vez queda viejo y el siguiente
  // POST se rechaza con 403.
  it('no escribe el header de CSRF a mano', async () => {
    let capturado: InternalAxiosRequestConfig | undefined

    await api.post('/auth/logout', null, {
      adapter: transporte((config) => {
        capturado = config

        return null
      }),
    })

    expect(capturado?.headers.get('X-XSRF-TOKEN')).toBeUndefined()
    expect(api.defaults.xsrfCookieName).toBe('XSRF-TOKEN')
    expect(api.defaults.xsrfHeaderName).toBe('X-XSRF-TOKEN')
  })
})
