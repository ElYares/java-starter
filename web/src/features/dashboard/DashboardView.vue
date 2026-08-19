<script setup lang="ts">
import { useAuthStore } from '../../app/stores/auth'

// El saludo sale del store, no de una segunda llamada al API. El guard ya pidio
// `/me` para poder dejar pasar: pedirlo otra vez aqui seria pagar dos veces por
// el mismo dato y dejar la vista con un parpadeo sin nombre mientras llega.
const sesion = useAuthStore()
</script>

<template>
  <section>
    <h1 class="titulo">Hola, {{ sesion.perfil?.displayName }}</h1>

    <p class="lead">
      Tu sesion sobrevivio al arranque en frio. Recarga con F5 y en ningun momento
      veras la pantalla de login.
    </p>
  </section>
</template>

<style scoped>
.titulo {
  margin: 0 0 var(--space-2);
  font-size: 1.75rem;
}

.lead {
  margin: 0;
  color: var(--text);
}
</style>
