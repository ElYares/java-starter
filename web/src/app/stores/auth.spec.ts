import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { api } from '../../shared/api/client'
import { servidorFalso } from '../../test/api'
import { useAuthStore } from './auth'

const PERFIL = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'admin@java-starter.localhost',
  displayName: 'Admin',
  roles: ['ADMIN'],
}

const NO_AUTENTICADO = {
  status: 401,
  data: { code: 'UNAUTHENTICATED', detail: 'No has iniciado sesion o tu sesion expiro' },
}

describe('store de sesion', () => {
  const original = api.defaults.adapter

  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    api.defaults.adapter = original
  })

  it('empieza sin haber preguntado', () => {
    expect(useAuthStore().estado).toBe('idle')
  })

  it('con 200 queda autenticado y con perfil', async () => {
    api.defaults.adapter = servidorFalso({ '/auth/me': { status: 200, data: PERFIL } }).adapter

    const sesion = useAuthStore()

    expect(await sesion.asegurarSesion()).toBe('authenticated')
    expect(sesion.perfil).toEqual(PERFIL)
    expect(sesion.autenticado).toBe(true)
  })

  // Las dos pruebas que sostienen E2. Si afirman lo mismo, el guard no puede
  // distinguir una sesion vencida de un backend caido.
  it('con 401 queda anonimo, no no-disponible', async () => {
    api.defaults.adapter = servidorFalso({ '/auth/me': NO_AUTENTICADO }).adapter

    const sesion = useAuthStore()

    expect(await sesion.asegurarSesion()).toBe('anonymous')
    expect(sesion.perfil).toBeNull()
  })

  it('sin servidor queda no-disponible, no anonimo', async () => {
    api.defaults.adapter = servidorFalso({ '/auth/me': { status: 0 } }).adapter

    expect(await useAuthStore().asegurarSesion()).toBe('unavailable')
  })

  it('no vuelve a preguntar cuando ya hubo respuesta', async () => {
    const servidor = servidorFalso({ '/auth/me': { status: 200, data: PERFIL } })
    api.defaults.adapter = servidor.adapter

    const sesion = useAuthStore()
    await sesion.asegurarSesion()
    await sesion.asegurarSesion()

    expect(servidor.veces('/auth/me')).toBe(1)
  })

  it('comparte una sola consulta entre llamadas simultaneas', async () => {
    const servidor = servidorFalso({ '/auth/me': { status: 200, data: PERFIL } })
    api.defaults.adapter = servidor.adapter

    const sesion = useAuthStore()
    await Promise.all([sesion.asegurarSesion(), sesion.asegurarSesion(), sesion.asegurarSesion()])

    expect(servidor.veces('/auth/me')).toBe(1)
  })

  // El boton de reintentar de la vista de disponibilidad es esto y nada mas.
  it('vuelve a preguntar cuando quedo no-disponible', async () => {
    let caido = true
    const servidor = servidorFalso({
      '/auth/me': () => (caido ? { status: 0 } : { status: 200, data: PERFIL }),
    })
    api.defaults.adapter = servidor.adapter

    const sesion = useAuthStore()
    expect(await sesion.asegurarSesion()).toBe('unavailable')

    caido = false

    expect(await sesion.asegurarSesion()).toBe('authenticated')
    expect(servidor.veces('/auth/me')).toBe(2)
  })

  it('un login rechazado lanza y no deja la sesion abierta', async () => {
    api.defaults.adapter = servidorFalso({
      '/auth/login': { status: 401, data: { code: 'UNAUTHENTICATED', detail: 'Credenciales invalidas' } },
    }).adapter

    const sesion = useAuthStore()

    await expect(sesion.iniciarSesion('a@b.c', 'mala')).rejects.toThrow()
    expect(sesion.autenticado).toBe(false)
  })

  it('tras el login pide el perfil a /me y no lo inventa', async () => {
    const servidor = servidorFalso({
      '/auth/login': { status: 204 },
      '/auth/me': { status: 200, data: PERFIL },
    })
    api.defaults.adapter = servidor.adapter

    const sesion = useAuthStore()
    await sesion.iniciarSesion('admin@java-starter.localhost', 'cambiame')

    expect(sesion.perfil).toEqual(PERFIL)
    expect(servidor.veces('/auth/me')).toBe(1)
  })

  it('el logout limpia el estado aunque la peticion falle', async () => {
    api.defaults.adapter = servidorFalso({
      '/auth/me': { status: 200, data: PERFIL },
      '/auth/logout': { status: 0 },
    }).adapter

    const sesion = useAuthStore()
    await sesion.asegurarSesion()

    await expect(sesion.cerrarSesion()).rejects.toThrow()
    expect(sesion.estado).toBe('anonymous')
    expect(sesion.perfil).toBeNull()
  })

  it('meta.roles es "alguno de estos", no "todos"', async () => {
    api.defaults.adapter = servidorFalso({
      '/auth/me': { status: 200, data: { ...PERFIL, roles: ['USER'] } },
    }).adapter

    const sesion = useAuthStore()
    await sesion.asegurarSesion()

    expect(sesion.tieneAlgunRol(['ADMIN', 'USER'])).toBe(true)
    expect(sesion.tieneAlgunRol(['ADMIN'])).toBe(false)
  })
})
