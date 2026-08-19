/** Donde aterriza quien entra sin pedir nada en concreto. */
export const RUTA_POR_OMISION = '/dashboard'

/**
 * Normaliza el `destino` que viaja en la query.
 *
 * Llega de la URL, asi que llega de fuera. Sin este filtro, un enlace a
 * `/login?destino=//evil.com` convierte la propia pantalla de login en un
 * redirector abierto: `//evil.com` es una URL relativa al protocolo y el
 * navegador la resuelve contra otro origen. La barra invertida esta cubierta
 * porque varios navegadores la normalizan a `/`.
 *
 * Vive aparte del router a proposito: las vistas lo necesitan, y si estuviera en
 * `router/index.ts` —que a su vez las importa— habria un ciclo de modulos.
 */
export function rutaSegura(destino: unknown): string {
  if (typeof destino !== 'string' || !destino.startsWith('/')) {
    return RUTA_POR_OMISION
  }

  if (destino.startsWith('//') || destino.includes('\\')) {
    return RUTA_POR_OMISION
  }

  return destino
}
