<script setup lang="ts">
// Pagina unica del paso 2 de la fase 0. Sin router, sin store y sin capa de
// API todavia: lo que se verifica aqui es que el contenedor sirva la SPA y que
// el HMR reaccione a los cambios del bind mount. La estructura real (app/,
// shared/, features/) llega con la fase 1; ver docs/05-frontend.md.
import { ref } from 'vue'

const contador = ref(0)
</script>

<template>
  <main>
    <h1>java-starter</h1>
    <p class="lead">El contenedor <code>web</code> esta sirviendo la SPA.</p>

    <p>
      Fase 0 completa: entras por <code>java-starter.localhost</code>, el
      servicio <code>edge</code> rutea <code>/api</code> al backend y el resto
      aqui, y no hay un solo puerto publicado en el host.
    </p>

    <button type="button" @click="contador++">
      Estado local: {{ contador }}
    </button>

    <p class="hint">
      Prueba del HMR: sube el contador, edita este texto en
      <code>web/src/App.vue</code> y guarda. Debe cambiar sin recargar y sin que
      el contador vuelva a cero.
    </p>
  </main>
</template>

<style scoped>
main {
  max-width: 36rem;
  margin: 0 auto;
  padding: 4rem 1.5rem;
}

h1 {
  margin: 0 0 0.25rem;
  font-size: 2rem;
}

.lead {
  margin: 0 0 1.5rem;
  color: var(--accent);
  font-weight: 600;
}

button {
  margin: 1.5rem 0;
}

.hint {
  font-size: 0.875rem;
  opacity: 0.75;
}
</style>
