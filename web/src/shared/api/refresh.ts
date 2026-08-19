import axios from 'axios'
import type { AxiosInstance } from 'axios'

// La pista de la Decision 014. No es una credencial: solo dice "aqui hubo
// sesion alguna vez", y el servidor jamas la mira. La emite el backend en el
// login y en cada rotacion; ver `SessionCookies.hint()`.
const PISTA = 'has_session'

// Rutas donde un 401 nunca se responde con un refresh.
//
// `/auth/refresh` es obligatorio: sin el, el 401 del refresh entra a este mismo
// interceptor y dispara otro refresh, para siempre. `/auth/login` es economia —
// ahi un 401 son credenciales malas, y refrescar para reintentar el mismo login
// con la misma contrasena mala gasta dos peticiones para llegar al mismo 401.
const SIN_REFRESH = ['/auth/refresh', '/auth/login']

// La marca del reintento viaja en el config porque no hay donde mas ponerla.
//
// Un `WeakSet` de configs no sirve: `instancia.request(config)` no reenvia ese
// objeto, lo fusiona con los defaults y produce otro, asi que la identidad se
// pierde justo en el momento en que hace falta. Una propiedad si sobrevive a la
// fusion, que es como Axios espera que se haga.
declare module 'axios' {
  export interface AxiosRequestConfig {
    /** Puesta por el interceptor de refresh. Un reintento no se reintenta. */
    reintentadoTrasRefresh?: boolean
  }
}

type Aviso = () => void

const avisos = new Set<Aviso>()

/**
 * Registra a quien quiera enterarse de que la sesion se perdio para siempre.
 *
 * Se dispara solo cuando **habia** sesion y el refresh la enterro: un visitante
 * anonimo que recibe un `401` no perdio nada y no lo dispara.
 *
 * Existe porque este modulo no puede tocar el store ni el router. `shared/` no
 * depende de `app/` — al reves si — y un `import` de `app/stores/auth` aqui
 * invertiria esa flecha y volveria `shared/api` inservible para cualquier otro
 * proyecto. La fase 4 conecta el store y el router por aqui.
 *
 * Devuelve la funcion para darse de baja.
 */
export function onSessionLost(aviso: Aviso): () => void {
  avisos.add(aviso)

  return () => {
    avisos.delete(aviso)
  }
}

/** El refresh en vuelo, o `null`. Es todo el mecanismo de "uno y no cinco". */
let enVuelo: Promise<void> | null = null

/**
 * Monta el refresh sobre la instancia.
 *
 * **Se registra antes que el normalizador de `ApiError`**, y el orden no es
 * gusto. Aqui hace falta el `config` de la peticion original para poder
 * reintentarla, y eso solo lo trae el error de Axios: un `ApiError` no lo lleva
 * ni debe llevarlo, porque es el tipo que ven las vistas y un config de
 * transporte no tiene nada que hacer ahi. De paso, un reintento que funciona ya
 * no produce ningun error, asi que el normalizador ni se entera.
 */
export function instalarRefresh(instancia: AxiosInstance): void {
  instancia.interceptors.response.use(
    (respuesta) => respuesta,
    async (error: unknown) => {
      const config = axios.isAxiosError(error) ? error.config : undefined

      // Un `401` y nada mas. Sin respuesta no hay 401 que interpretar: eso es
      // una caida, y refrescar contra un servidor que no contesta solo agrega
      // una peticion perdida al incidente.
      if (!config || !axios.isAxiosError(error) || error.response?.status !== 401) {
        throw error
      }

      if (config.reintentadoTrasRefresh || esExcluida(config.url)) {
        throw error
      }

      // El visitante anonimo se detiene aqui, y esa es la Decision 014 entera.
      if (!hayPista()) {
        throw error
      }

      try {
        await refrescarUnaVez(instancia)
      } catch {
        // La pista la borra el cliente y no el backend: en este punto ya sabemos
        // que la sesion murio, y hacerlo en el servidor obligaria a colgar
        // cabeceras de la excepcion del refresh para algo que aqui es una linea.
        olvidarPista()
        avisarSesionPerdida()

        // Se propaga el error original, no el del refresh: quien llamo pidio
        // `/me`, no un refresh, y el fallo que le sirve es el de lo que pidio.
        throw error
      }

      config.reintentadoTrasRefresh = true

      // El reintento vuelve a pasar por el adaptador, que relee la cookie de
      // CSRF: el `X-XSRF-TOKEN` que sale es el rotado por el refresh, no el que
      // llevaba la peticion que fallo. Es gratis solo porque nadie escribe ese
      // header a mano — ver el comentario de `client.ts`.
      return instancia.request(config)
    },
  )
}

/**
 * Un refresh, no cinco.
 *
 * Cinco peticiones que reciben `401` a la vez llegan aqui cinco veces y las
 * cinco esperan a la misma promesa. Sin esto se disparan cinco
 * `POST /auth/refresh`, cada uno rota el token del anterior y cuatro quedan
 * invalidados: el usuario acaba expulsado por haber tenido la sesion **valida**
 * en cinco pestanas. Es el criterio de CU-002 que el backend no podia cerrar.
 *
 * El `finally` limpia la promesa para que el siguiente `401` — el de dentro de
 * quince minutos — vuelva a poder refrescar.
 */
function refrescarUnaVez(instancia: AxiosInstance): Promise<void> {
  enVuelo ??= instancia
    .post('/auth/refresh')
    .then(() => undefined)
    .finally(() => {
      enVuelo = null
    })

  return enVuelo
}

function esExcluida(url: string | undefined): boolean {
  return url !== undefined && SIN_REFRESH.some((ruta) => url.startsWith(ruta))
}

function hayPista(): boolean {
  return document.cookie
    .split(';')
    .some((cookie) => cookie.trim().startsWith(`${PISTA}=`))
}

/**
 * Borra la pista.
 *
 * El `Path=/` tiene que ser el mismo con el que se emitio o el navegador crea
 * una cookie nueva en la ruta actual y deja viva la original: parece borrada y
 * no lo esta. Es la misma trampa que cuidan las cookies de borrado del backend.
 */
function olvidarPista(): void {
  document.cookie = `${PISTA}=; Path=/; Max-Age=0; SameSite=Lax`
}

function avisarSesionPerdida(): void {
  for (const aviso of avisos) {
    try {
      aviso()
    } catch {
      // Un suscriptor que revienta no puede secuestrar el error original de la
      // peticion, que es lo que la vista esta esperando.
    }
  }
}
