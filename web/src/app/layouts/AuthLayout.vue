<script setup lang="ts">
// Centrado y sin navegacion. No hay sidebar ni encabezado de usuario porque en
// las rutas que lo usan todavia no se sabe quien es el usuario — o ya se sabe
// que no hay ninguno.
//
// Se asigna desde la ruta (`meta.layout`), nunca desde dentro de la vista: con
// quince vistas, meter el marco como componente de cada una es lo que se paga
// cuando cambia el marco.
</script>

<template>
  <main class="marco">
    <slot />
  </main>
</template>

<style scoped>
.marco {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 100vh;
  max-width: 24rem;
  margin: 0 auto;
  padding: var(--space-6);
}
</style>
