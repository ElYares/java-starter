import { createRouter, createWebHistory } from 'vue-router'
import type { Router, RouteRecordRaw } from 'vue-router'

import { onSessionLost } from '../../shared/api/refresh'
import { useAuthStore } from '../stores/auth'
import { RUTA_POR_OMISION, rutaSegura } from './destino'

// El `meta` tipado es lo que hace que una ruta con `meta: { publico: true }` —
// mal escrito — no compile. Sin esto el guard leeria `undefined` y trataria la
// ruta como privada, que es el fallo seguro pero silencioso.
declare module 'vue-router' {
  interface RouteMeta {
    /**
     * Abre la ruta a cualquiera. **Su ausencia significa privada**: ver la
     * Decision 013. No existe `requiresAuth` y no se acepta.
     */
    public?: boolean

    /** Restriccion adicional sobre una ruta que ya es privada. Nunca lo que la vuelve privada. */
    roles?: readonly string[]

    /** El layout se asigna aqui y nunca dentro de la vista. */
    layout?: 'auth' | 'app'
  }
}

const rutas: RouteRecordRaw[] = [
  { path: '/', redirect: RUTA_POR_OMISION },
  {
    path: '/login',
    name: 'login',
    component: () => import('../../features/auth/LoginView.vue'),
    meta: { public: true, layout: 'auth' },
  },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('../../features/dashboard/DashboardView.vue'),
    meta: { layout: 'app' },
  },
  {
    path: '/sin-permiso',
    name: 'sin-permiso',
    component: () => import('../views/ForbiddenView.vue'),
    // Privada a proposito: para que te digan que te falta un rol, primero hay
    // que saber quien eres.
    meta: { layout: 'app' },
  },
  {
    path: '/no-disponible',
    name: 'no-disponible',
    component: () => import('../views/UnavailableView.vue'),
    // Publica por necesidad: es la vista a la que el guard manda cuando no puede
    // preguntar quien eres. Privada, el guard se mandaria a si mismo en bucle.
    meta: { public: true, layout: 'auth' },
  },
]

export function crearRouter(history = createWebHistory()): Router {
  const router = createRouter({ history, routes: rutas })

  /**
   * Un solo `beforeEach` lee `meta` y decide. Nunca un `if (rol === 'ADMIN')`
   * dentro de un componente.
   *
   * El guard es **conveniencia, no seguridad**: la autorizacion real vive en el
   * servidor y ocultar una vista no protege un endpoint. Por eso mismo el default
   * barato tiene que ser el conservador — si se va a equivocar, que se equivoque
   * de mas.
   */
  router.beforeEach(async (to) => {
    // A1. Antes de cualquier peticion: una ruta publica no espera nada.
    if (to.meta.public) {
      return true
    }

    const sesion = useAuthStore()

    // El paso 3 de CU-003 entero: se espera la respuesta antes de decidir. Sin
    // este `await` el guard decide con el store vacio y manda a login a un
    // usuario con sesion valida, que es el bug que este caso de uso viene a
    // cerrar.
    const estado = await sesion.asegurarSesion()

    // E2. No es "no autenticado", y confundirlos es lo que expulsa a todos los
    // usuarios durante una caida de treinta segundos. Las cookies quedan
    // intactas: no se cierra nada, solo no se pudo preguntar.
    if (estado === 'unavailable') {
      return { name: 'no-disponible', query: { destino: to.fullPath } }
    }

    // A3. Con el destino guardado, o tras el login el usuario aterriza en la
    // raiz y no en lo que pidio.
    if (estado === 'anonymous') {
      return { name: 'login', query: { destino: to.fullPath } }
    }

    // A2. A la vista de sin permiso, no a login: el usuario si esta autenticado,
    // y mandarlo a login sugiere que volver a entrar arreglaria algo.
    if (to.meta.roles && !sesion.tieneAlgunRol(to.meta.roles)) {
      return { name: 'sin-permiso' }
    }

    return true
  })

  return router
}

/**
 * Cierra la costura que la fase 3 dejo abierta.
 *
 * El interceptor no puede tocar el store ni el router —`shared/` no depende de
 * `app/`— asi que los dos se conectan aqui, que es el modulo que los tiene a
 * mano. Devuelve la baja: sin ella, cada router creado en una prueba dejaria un
 * suscriptor vivo y el siguiente test heredaria los del anterior.
 */
export function conectarSesionPerdida(router: Router): () => void {
  return onSessionLost(() => {
    useAuthStore().sesionPerdida()

    // `replace` y no `push`: el usuario no eligio salir, asi que el boton de
    // atras no deberia devolverlo a una vista que ya no puede ver.
    void router.replace({
      name: 'login',
      query: { destino: rutaSegura(router.currentRoute.value.fullPath) },
    })
  })
}
