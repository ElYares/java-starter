import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import type { Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { api } from '../../shared/api/client'
import { servidorFalso } from '../../test/api'
import { useAuthStore } from '../stores/auth'
import { conectarSesionPerdida, crearRouter } from './index'
import { RUTA_POR_OMISION, rutaSegura } from './destino'

const ADMIN = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'admin@java-starter.localhost',
  displayName: 'Admin',
  roles: ['ADMIN'],
}

const USER = { ...ADMIN, roles: ['USER'] }

const NO_AUTENTICADO = {
  status: 401,
  data: { code: 'UNAUTHENTICATED', detail: 'No has iniciado sesion o tu sesion expiro' },
}

/** Monta el guard sobre un servidor dado. Historia en memoria: sin `location`. */
function conSesion(me: { status: number; data?: unknown }) {
  const servidor = servidorFalso({ '/auth/me': me, '/auth/logout': { status: 204 } })
  api.defaults.adapter = servidor.adapter

  return { router: crearRouter(createMemoryHistory()), servidor }
}

describe('guard del router', () => {
  const original = api.defaults.adapter

  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    api.defaults.adapter = original
  })

  // Flujo principal de CU-003.
  it('con sesion valida sirve la ruta privada pedida', async () => {
    const { router } = conSesion({ status: 200, data: ADMIN })

    await router.push('/dashboard')

    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  // Decision 013. La prueba que la decision misma pide: una ruta declarada sin
  // ninguna `meta` no se sirve a un anonimo. Se agrega en caliente para simular
  // exactamente el descuido que se teme — alguien anade una ruta y no piensa en
  // la bandera.
  it('una ruta nueva sin ninguna meta nace privada', async () => {
    const { router } = conSesion(NO_AUTENTICADO)
    router.addRoute({ path: '/facturacion', name: 'facturacion', component: { template: '<p/>' } })

    await router.push('/facturacion')

    expect(router.currentRoute.value.name)
      .not.toBe('facturacion')
    expect(router.currentRoute.value.name).toBe('login')
  })

  // A1. Y ademas no gasta una peticion: una ruta publica no pregunta nada.
  it('una ruta publica pasa sin preguntar por la sesion', async () => {
    const { router, servidor } = conSesion(NO_AUTENTICADO)

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe('login')
    expect(servidor.veces('/auth/me')).toBe(0)
  })

  // A3. Sin el destino, tras el login el usuario aterriza en la raiz y no en lo
  // que pidio.
  it('sin sesion manda a login guardando el destino', async () => {
    const { router } = conSesion(NO_AUTENTICADO)

    await router.push('/dashboard?tab=uploads')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.destino).toBe('/dashboard?tab=uploads')
  })

  // E2, y el criterio entero: confundir "no disponible" con "no autenticado" es
  // lo que hace que una caida de treinta segundos expulse a todos los usuarios.
  it('con el backend caido manda a disponibilidad y no a login', async () => {
    const { router } = conSesion({ status: 0 })

    await router.push('/dashboard')

    expect(router.currentRoute.value.name).toBe('no-disponible')
    expect(router.currentRoute.value.query.destino).toBe('/dashboard')
  })

  // A2. A login sugeriria que volver a entrar arreglaria algo, y no lo arregla.
  it('con sesion valida y sin el rol manda a sin-permiso, no a login', async () => {
    const { router } = conSesion({ status: 200, data: USER })
    router.addRoute({
      path: '/admin',
      name: 'admin',
      component: { template: '<p/>' },
      meta: { layout: 'app', roles: ['ADMIN'] },
    })

    await router.push('/admin')

    expect(router.currentRoute.value.name).toBe('sin-permiso')
  })

  it('con el rol requerido deja pasar', async () => {
    const { router } = conSesion({ status: 200, data: ADMIN })
    router.addRoute({
      path: '/admin',
      name: 'admin',
      component: { template: '<p/>' },
      meta: { layout: 'app', roles: ['ADMIN'] },
    })

    await router.push('/admin')

    expect(router.currentRoute.value.name).toBe('admin')
  })

  it('no vuelve a pedir /me en cada navegacion', async () => {
    const { router, servidor } = conSesion({ status: 200, data: ADMIN })

    await router.push('/dashboard')
    await router.push('/sin-permiso')
    await router.push('/dashboard')

    expect(servidor.veces('/auth/me')).toBe(1)
  })
})

describe('conectarSesionPerdida', () => {
  const original = api.defaults.adapter
  let baja: (() => void) | null = null

  beforeEach(() => {
    setActivePinia(createPinia())
  })

  afterEach(() => {
    baja?.()
    baja = null
    api.defaults.adapter = original
    document.cookie = 'has_session=; Path=/; Max-Age=0'
  })

  // Lo que la fase 3 dejo colgando: el interceptor avisa, y esto es lo que
  // convierte el aviso en un store limpio y una redireccion.
  it('deja el store anonimo y manda a login cuando el refresh entierra la sesion', async () => {
    let perfilVivo = true
    const servidor = servidorFalso({
      '/auth/me': () => (perfilVivo ? { status: 200, data: ADMIN } : NO_AUTENTICADO),
      '/auth/refresh': NO_AUTENTICADO,
    })
    api.defaults.adapter = servidor.adapter

    const router: Router = crearRouter(createMemoryHistory())
    baja = conectarSesionPerdida(router)

    await router.push('/dashboard')
    expect(router.currentRoute.value.name).toBe('dashboard')

    // A partir de aqui el access token esta vencido y el refresh va a fallar.
    perfilVivo = false
    document.cookie = 'has_session=1; Path=/'

    await api.get('/auth/me').catch(() => undefined)
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))

    expect(useAuthStore().estado).toBe('anonymous')
    expect(useAuthStore().perfil).toBeNull()
  })
})

describe('rutaSegura', () => {
  // El destino llega de la query, o sea de fuera. Sin filtro, la pantalla de
  // login se convierte en un redirector abierto.
  it('rechaza lo que saldria del sitio', () => {
    expect(rutaSegura('//evil.com')).toBe(RUTA_POR_OMISION)
    expect(rutaSegura('/\\evil.com')).toBe(RUTA_POR_OMISION)
    expect(rutaSegura('https://evil.com')).toBe(RUTA_POR_OMISION)
    expect(rutaSegura(undefined)).toBe(RUTA_POR_OMISION)
    expect(rutaSegura(['/a', '/b'])).toBe(RUTA_POR_OMISION)
  })

  it('acepta una ruta interna con query', () => {
    expect(rutaSegura('/dashboard?tab=uploads')).toBe('/dashboard?tab=uploads')
  })
})
