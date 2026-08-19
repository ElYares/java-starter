import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { ApiError } from '../../shared/api/ApiError'
import { api } from '../../shared/api/client'

/**
 * Lo que el frontend sabe del usuario, y su unica fuente es `GET /auth/me`.
 *
 * Espeja `MeResponse` del backend. Cuando exista el cliente generado desde
 * OpenAPI (HU-004) este tipo se borra y se importa el generado.
 */
export interface Perfil {
  id: string
  email: string
  displayName: string
  roles: string[]
}

/**
 * Los cinco estados, y son cinco porque cuatro no alcanzan.
 *
 * `idle` y `loading` no son lo mismo: el primero es "nadie ha preguntado", el
 * segundo "se pregunto y no ha vuelto". El guard necesita distinguirlos para
 * saber si le toca preguntar o esperar.
 *
 * `anonymous` y `unavailable` tampoco: el primero es que el servidor dijo que no
 * hay sesion, el segundo que no hubo servidor. Colapsarlos es lo que hace que una
 * caida de treinta segundos expulse a todos los usuarios — E2 de CU-003, y la
 * razon por la que `ApiError` distingue `answered` de `unavailable`.
 */
export type EstadoSesion = 'idle' | 'loading' | 'authenticated' | 'anonymous' | 'unavailable'

export const useAuthStore = defineStore('auth', () => {
  const estado = ref<EstadoSesion>('idle')
  const perfil = ref<Perfil | null>(null)

  const autenticado = computed(() => estado.value === 'authenticated')

  /** La consulta a `/me` en vuelo, o `null`. Mismo motivo que en el refresh. */
  let enVuelo: Promise<EstadoSesion> | null = null

  /**
   * Deja el estado resuelto y lo devuelve.
   *
   * Idempotente cuando ya hubo respuesta: con `authenticated` o `anonymous` no
   * vuelve a preguntar, asi que el guard puede llamarlo en cada navegacion sin
   * gastar una peticion por clic.
   *
   * **`unavailable` si vuelve a preguntar**, y eso es el boton de reintentar de
   * la vista de disponibilidad: no hace falta otra funcion para el.
   *
   * Nunca lanza. Un fallo es un estado, no una excepcion: el guard tiene que
   * poder decidir con lo que sea que haya pasado.
   */
  async function asegurarSesion(): Promise<EstadoSesion> {
    if (estado.value === 'authenticated' || estado.value === 'anonymous') {
      return estado.value
    }

    // Dos navegaciones simultaneas —o el guard y la vista a la vez— comparten
    // una sola consulta en vez de pedir `/me` dos veces.
    enVuelo ??= preguntar().finally(() => {
      enVuelo = null
    })

    return enVuelo
  }

  async function preguntar(): Promise<EstadoSesion> {
    estado.value = 'loading'

    try {
      const { data } = await api.get<Perfil>('/auth/me')
      perfil.value = data
      estado.value = 'authenticated'
    } catch (error) {
      perfil.value = null
      // Toda la fase 2 existe para que esta linea se pueda escribir.
      estado.value = ApiError.from(error).unavailable ? 'unavailable' : 'anonymous'
    }

    return estado.value
  }

  /**
   * Inicia sesion y deja el perfil cargado.
   *
   * Lanza si el login es rechazado — la vista necesita el `ApiError` para
   * distinguir credenciales invalidas de un limite de intentos agotado. Lo que
   * no lanza es el `/me` que sigue: si el login funciono, el usuario ya tiene
   * sesion, y un `/me` que falle es un problema de disponibilidad y no del login.
   *
   * El perfil se pide con `/me` y no se lee de la respuesta del login, que no
   * trae ninguno: un solo lugar define que sabe el frontend del usuario.
   */
  async function iniciarSesion(email: string, password: string): Promise<void> {
    await api.post('/auth/login', { email, password })
    await preguntar()
  }

  /**
   * Cierra la sesion.
   *
   * El estado local se limpia en el `finally` por la misma razon por la que el
   * backend borra las cookies pase lo que pase: quien pidio salir tiene que
   * quedar fuera, y si la peticion falla el resultado se consigue igual.
   */
  async function cerrarSesion(): Promise<void> {
    try {
      await api.post('/auth/logout')
    } finally {
      perfil.value = null
      estado.value = 'anonymous'
    }
  }

  /**
   * Lo llama el interceptor cuando el refresh entierra la sesion.
   *
   * Deja el estado en `anonymous` y no en `idle`: la pregunta ya se hizo y la
   * respuesta fue que no hay sesion. Con `idle`, el guard preguntaria otra vez
   * y gastaria un `/me` para enterarse de lo que ya sabe.
   */
  function sesionPerdida(): void {
    perfil.value = null
    estado.value = 'anonymous'
  }

  /** `meta.roles` es "alguno de estos", no "todos estos". */
  function tieneAlgunRol(requeridos: readonly string[]): boolean {
    const propios = perfil.value?.roles ?? []

    return requeridos.some((rol) => propios.includes(rol))
  }

  return {
    estado,
    perfil,
    autenticado,
    asegurarSesion,
    iniciarSesion,
    cerrarSesion,
    sesionPerdida,
    tieneAlgunRol,
  }
})
