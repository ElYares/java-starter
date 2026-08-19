<script setup lang="ts">
import { useRouter } from 'vue-router'

import AppButton from '../../shared/ui/AppButton.vue'
import { useAuthStore } from '../stores/auth'

const sesion = useAuthStore()
const router = useRouter()

// El nombre sale del store y no de una segunda llamada al API: el guard ya pidio
// `/me` para poder dejar pasar, asi que preguntarlo otra vez seria pagar dos
// veces por el mismo dato.

async function salir() {
  await sesion.cerrarSesion()
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="marco">
    <header class="barra">
      <strong class="barra__marca">java-starter</strong>

      <div class="barra__usuario">
        <!-- Puede ser null mientras el store se resuelve; el layout no decide
             sobre la sesion, eso es del guard. -->
        <span v-if="sesion.perfil">{{ sesion.perfil.displayName }}</span>
        <AppButton variant="ghost" @click="salir">Salir</AppButton>
      </div>
    </header>

    <main class="contenido">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.marco {
  min-height: 100vh;
}

.barra {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-6);
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}

.barra__marca {
  color: var(--accent-strong);
}

.barra__usuario {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.contenido {
  max-width: 60rem;
  margin: 0 auto;
  padding: var(--space-8) var(--space-6);
}
</style>
