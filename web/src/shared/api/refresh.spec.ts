import { AxiosError } from 'axios'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from './ApiError'
import { api } from './client'
import { onSessionLost } from './refresh'

const PISTA = 'has_session=1; Path=/'
const SIN_PISTA = 'has_session=; Path=/; Max-Age=0'

/**
 * Un servidor de mentira, con la unica regla que importa: `/auth/me` responde
 * `401` hasta que un refresh prospera, y `200` despues.
 *
 * Va en `defaults` y no por peticion porque el `POST /auth/refresh` lo emite el
 * interceptor, no la prueba, y un adaptador por peticion no llegaria hasta ahi.
 */
function servidor(refreshResponde: 204 | 401) {
  let refreshes = 0
  let sesionViva = false

  const adapter: AxiosAdapter = async (config) => {
    if (config.url === '/auth/refresh') {
      refreshes += 1

      if (refreshResponde === 401) {
        throw no401(config)
      }

      sesionViva = true

      return respuesta(config, 204, null)
    }

    if (!sesionViva) {
      throw no401(config)
    }

    return respuesta(config, 200, { id: 'u-1', displayName: 'Admin' })
  }

  return { adapter, refreshes: () => refreshes }
}

function no401(config: InternalAxiosRequestConfig): AxiosError {
  const error = new AxiosError('Unauthorized', AxiosError.ERR_BAD_REQUEST, config)
  error.response = respuesta(config, 401, {
    code: 'UNAUTHENTICATED',
    detail: 'No has iniciado sesion o tu sesion expiro',
  })

  return error
}

function respuesta(
  config: InternalAxiosRequestConfig,
  status: number,
  data: unknown,
): AxiosResponse {
  return { data, status, statusText: '', headers: {}, config }
}

describe('interceptor de refresh', () => {
  const original = api.defaults.adapter

  beforeEach(() => {
    document.cookie = SIN_PISTA
  })

  afterEach(() => {
    api.defaults.adapter = original
    document.cookie = SIN_PISTA
  })

  // CU-002: "dado un at vencido, la accion se completa sin que aparezca la
  // pantalla de login". Es tambien E1 de CU-003.
  it('renueva la sesion y reintenta, sin que el usuario vea el login', async () => {
    const { adapter, refreshes } = servidor(204)
    api.defaults.adapter = adapter
    document.cookie = PISTA

    const perfil = await api.get('/auth/me')

    expect(perfil.data).toEqual({ id: 'u-1', displayName: 'Admin' })
    expect(refreshes()).toBe(1)
  })

  // CU-002: "dado cinco peticiones concurrentes que reciben 401, se observa
  // exactamente un POST /auth/refresh, no cinco". Cinco rotarian el token cinco
  // veces y cuatro se invalidarian entre ellas.
  it('hace un solo refresh para cinco 401 simultaneos', async () => {
    const { adapter, refreshes } = servidor(204)
    api.defaults.adapter = adapter
    document.cookie = PISTA

    const todas = await Promise.all(
      Array.from({ length: 5 }, () => api.get('/auth/me')),
    )

    expect(todas.map((r) => r.status)).toEqual([200, 200, 200, 200, 200])
    expect(refreshes()).toBe(1)
  })

  // CU-002: "dado un refresh que responde 401, no se dispara otro refresh".
  it('no dispara un segundo refresh cuando el primero falla', async () => {
    const { adapter, refreshes } = servidor(401)
    api.defaults.adapter = adapter
    document.cookie = PISTA

    const perdida = vi.fn()
    const baja = onSessionLost(perdida)

    const error = await api.get('/auth/me').catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('UNAUTHENTICATED')
    expect(refreshes()).toBe(1)
    expect(perdida).toHaveBeenCalledOnce()
    expect(document.cookie).not.toContain('has_session')

    baja()
  })

  // El 401 del refresh entra a este mismo interceptor. Sin la exclusion de la
  // ruta, dispararia otro refresh, y ese otro, para siempre.
  it('no se llama a si mismo cuando el refresh es lo que da 401', async () => {
    const { adapter, refreshes } = servidor(401)
    api.defaults.adapter = adapter
    document.cookie = PISTA

    await expect(api.post('/auth/refresh')).rejects.toBeInstanceOf(ApiError)

    expect(refreshes()).toBe(1)
  })

  // Decision 014. Sin pista no hubo sesion, asi que no hay nada que renovar y el
  // refresh solo agregaria una peticion perdida y un 401 en el log por visita.
  it('el visitante anonimo no pide un refresh que sabe que va a fallar', async () => {
    const { adapter, refreshes } = servidor(204)
    api.defaults.adapter = adapter

    const perdida = vi.fn()
    const baja = onSessionLost(perdida)

    const error = await api.get('/auth/me').catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.code).toBe('UNAUTHENTICATED')
    expect(refreshes()).toBe(0)
    // No perdio una sesion quien no la tenia.
    expect(perdida).not.toHaveBeenCalled()

    baja()
  })

  // Un 401 en el login son credenciales malas. Refrescar para reintentar el
  // mismo login con la misma contrasena mala gasta dos peticiones para llegar al
  // mismo sitio.
  it('no refresca ante un login rechazado', async () => {
    const { adapter, refreshes } = servidor(401)
    api.defaults.adapter = adapter
    document.cookie = PISTA

    await expect(api.post('/auth/login', { email: 'a@b.c', password: 'mala' }))
      .rejects.toBeInstanceOf(ApiError)

    expect(refreshes()).toBe(0)
  })

  // Ata la fase 2 con la 3: sin respuesta no hay 401 que interpretar. Refrescar
  // contra un servidor que no contesta agrega una peticion perdida al incidente.
  it('no refresca cuando no hubo servidor', async () => {
    let refreshes = 0
    api.defaults.adapter = async (config) => {
      if (config.url === '/auth/refresh') {
        refreshes += 1
      }

      throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config)
    }
    document.cookie = PISTA

    const error = await api.get('/auth/me').catch((e) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.unavailable).toBe(true)
    expect(refreshes).toBe(0)
  })
})
