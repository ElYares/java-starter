import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const DOMAIN = 'java-starter.localhost'

// El servidor escucha en 0.0.0.0 porque corre dentro de un contenedor: atado a
// 127.0.0.1 seria inalcanzable desde fuera de el. 'strictPort' evita que Vite
// se mude al 5174 en silencio y deje el mapeo de puertos apuntando a la nada.
//
// El bloque 'hmr' es el detalle que muerde y no da error claro. Sin
// 'clientPort: 80', el cliente de HMR abre el WebSocket contra el 5173 del
// host, que no esta publicado: el HMR queda muerto en silencio.
// Ver docs/06-infra-devherd.md.
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    // Vite acepta cualquier host bajo .localhost por omision, asi que hoy esto
    // es redundante. Se deja explicito porque deja de serlo en cuanto el
    // dominio no termine en .localhost.
    allowedHosts: [DOMAIN],
    hmr: {
      // El navegador habla con el proxy, no con Vite.
      clientPort: 80,
      host: DOMAIN,
    },
  },
})
