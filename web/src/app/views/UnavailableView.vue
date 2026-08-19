<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppButton from '../../shared/ui/AppButton.vue'
import StatusPanel from '../../shared/ui/StatusPanel.vue'
import { rutaSegura } from '../router/destino'
import { useAuthStore } from '../stores/auth'

const sesion = useAuthStore()
const router = useRouter()
const route = useRoute()

const reintentando = ref(false)

// E2 de CU-003. Lo que esta vista promete, y por lo que existe en vez de un
// simple redirect a login, es que **las cookies siguen intactas**: no se cerro
// nada, solo no se pudo preguntar.
//
// El reintento no necesita una funcion propia en el store: `asegurarSesion`
// vuelve a preguntar cuando el estado es `unavailable`, precisamente para esto.
async function reintentar() {
  reintentando.value = true

  try {
    if ((await sesion.asegurarSesion()) !== 'unavailable') {
      // Sigue siendo el guard quien decide: puede ser que la sesion se recupere
      // y pase, o que resulte que no hay sesion y mande a login.
      await router.replace(rutaSegura(route.query.destino))
    }
  } finally {
    reintentando.value = false
  }
}
</script>

<template>
  <StatusPanel
    tone="danger"
    title="No pudimos contactar al servidor"
    description="Tu sesion no se cerro y tus datos estan intactos: el problema es que el servidor no responde. Vuelve a intentarlo en un momento."
  >
    <AppButton :loading="reintentando" @click="reintentar">
      {{ reintentando ? 'Reintentando...' : 'Reintentar' }}
    </AppButton>
  </StatusPanel>
</template>
