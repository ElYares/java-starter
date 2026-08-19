<script setup lang="ts">
import { useRouter } from 'vue-router'

import AppButton from '../../shared/ui/AppButton.vue'
import StatusPanel from '../../shared/ui/StatusPanel.vue'
import { RUTA_POR_OMISION } from '../router/destino'

const router = useRouter()

// A2 de CU-003. Esta vista existe para no mandar a login a alguien que ya inicio
// sesion: mandarlo ahi sugiere que volver a entrar arreglaria algo, y no lo
// arregla — le falta un rol, no una sesion.
</script>

<template>
  <StatusPanel
    title="No tienes permiso"
    description="Tu sesion es valida, pero esta seccion pide un rol que tu cuenta no tiene. Si crees que deberia tenerlo, pidelo a quien administre el sistema."
  >
    <AppButton @click="router.push(RUTA_POR_OMISION)">Volver al inicio</AppButton>
  </StatusPanel>
</template>
