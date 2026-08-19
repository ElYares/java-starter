<script setup lang="ts">
// Pantalla completa de un estado que no es "datos". CU-003 la usa tres veces
// —sin permiso, backend caido y la espera de /me al arrancar— y las tres tienen
// la misma forma: un titulo, una explicacion y, a veces, una salida.
//
// El slot de acciones es opcional a proposito: la espera del arranque no ofrece
// ninguna, porque no hay nada que el usuario pueda hacer todavia.
//
// Cuando llegue AsyncSection en la fase 2, sus estados de vacio y de error se
// pintan con esto en vez de volver a inventarlos.
withDefaults(
  defineProps<{
    title: string
    description?: string
    tone?: 'neutral' | 'danger'
  }>(),
  { tone: 'neutral' },
)
</script>

<template>
  <section :class="['panel', `panel--${tone}`]">
    <h2 class="panel__titulo">{{ title }}</h2>
    <p v-if="description" class="panel__descripcion">{{ description }}</p>
    <div v-if="$slots.default" class="panel__acciones">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  max-width: 32rem;
  margin: 0 auto;
  padding: var(--space-8) var(--space-6);
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
}

.panel--danger {
  border-color: var(--danger);
}

.panel__titulo {
  margin: 0;
  color: var(--text-strong);
  font-size: 1.25rem;
  font-weight: 600;
}

.panel__descripcion {
  margin: 0;
}

.panel__acciones {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-2);
}
</style>
