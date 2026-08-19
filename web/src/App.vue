<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppLayout from './app/layouts/AppLayout.vue'
import AuthLayout from './app/layouts/AuthLayout.vue'
import { useAuthStore } from './app/stores/auth'
import StatusPanel from './shared/ui/StatusPanel.vue'

const route = useRoute()
const router = useRouter()
const sesion = useAuthStore()

// El layout sale de la ruta y no de la vista. Ver `meta.layout`.
const layout = computed(() => (route.meta.layout === 'app' ? AppLayout : AuthLayout))

// La primera navegacion del router es asincrona, asi que entre el `mount` y su
// resolucion el `RouterView` no tiene nada que pintar. Sin esta bandera eso se
// ve: un marco vacio parpadea antes de que el guard termine de preguntar `/me`.
const listo = ref(false)
void router.isReady().then(() => {
  listo.value = true
})

// La espera cubre los dos momentos: el arranque en frio, y un reintento desde la
// vista de disponibilidad.
const esperando = computed(() => !listo.value || sesion.estado === 'loading')
</script>

<template>
  <!-- Sin acciones a proposito: no hay nada que el usuario pueda hacer mientras
       se resuelve `/me`, y un boton muerto seria peor que ninguno. -->
  <StatusPanel
    v-if="esperando"
    title="Verificando tu sesion"
    description="Un momento: estamos comprobando si ya habias iniciado sesion."
  />

  <component :is="layout" v-else>
    <RouterView />
  </component>
</template>
