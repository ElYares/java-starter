import { AxiosError } from 'axios'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'

/**
 * Un servidor de mentira para las pruebas que pasan por `api`.
 *
 * Sustituye el adaptador y no `api` entero: asi la peticion recorre la cadena
 * real de interceptores —refresh y normalizacion incluidos— y lo unico que no
 * ocurre es el viaje por la red.
 *
 * `status: 0` significa "no hubo servidor", que es distinto de cualquier codigo
 * de error y es la mitad de lo que CU-003 necesita poder distinguir.
 */
export interface Respuesta {
  status: number
  data?: unknown
}

export function servidorFalso(rutas: Record<string, Respuesta | (() => Respuesta)>) {
  const llamadas: string[] = []

  const adapter: AxiosAdapter = async (config) => {
    const url = config.url ?? ''
    llamadas.push(url)

    const guion = rutas[url]

    if (guion === undefined) {
      throw conRespuesta(config, 404, { code: 'NOT_FOUND', detail: `sin guion para ${url}` })
    }

    const { status, data } = typeof guion === 'function' ? guion() : guion

    if (status === 0) {
      throw new AxiosError('Network Error', AxiosError.ERR_NETWORK, config)
    }

    if (status >= 400) {
      throw conRespuesta(config, status, data)
    }

    return respuesta(config, status, data ?? null)
  }

  return {
    adapter,
    llamadas,
    /** Cuantas veces se pidio esta ruta. Lo que hace verificable "una y no cinco". */
    veces: (url: string) => llamadas.filter((llamada) => llamada === url).length,
  }
}

function conRespuesta(
  config: InternalAxiosRequestConfig,
  status: number,
  data: unknown,
): AxiosError {
  const error = new AxiosError('Request failed', AxiosError.ERR_BAD_REQUEST, config)
  error.response = respuesta(config, status, data)

  return error
}

function respuesta(
  config: InternalAxiosRequestConfig,
  status: number,
  data: unknown,
): AxiosResponse {
  return { data, status, statusText: '', headers: {}, config }
}
