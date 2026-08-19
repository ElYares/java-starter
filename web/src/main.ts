import { createApp } from 'vue'
import { createPinia } from 'pinia'

import './style.css'
import App from './App.vue'
import { conectarSesionPerdida, crearRouter } from './app/router'

const app = createApp(App)

// Pinia primero, y el orden importa: instalar el router arranca su primera
// navegacion, esa navegacion corre el guard, y el guard llama a `useAuthStore()`.
// Al reves, revienta con "no active Pinia" en el arranque y el mensaje no
// apunta al orden de estas dos lineas.
app.use(createPinia())

const router = crearRouter()
conectarSesionPerdida(router)
app.use(router)

app.mount('#app')
