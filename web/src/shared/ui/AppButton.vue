<script setup lang="ts">
// Existe por dos razones que no son cosmeticas.
//
// El tipo por omision: en HTML un <button> dentro de un <form> vale
// type="submit" si nadie dice lo contrario, asi que un boton de "cancelar"
// termina enviando el formulario. Aqui el default es 'button' y enviar es una
// decision explicita.
//
// El color: --accent reprueba contraste como relleno (2.50:1). El par correcto
// es --accent-strong con --on-accent encima, y eso no se recuerda vista por
// vista.
withDefaults(
  defineProps<{
    variant?: 'primary' | 'ghost'
    type?: 'button' | 'submit'
    loading?: boolean
    disabled?: boolean
  }>(),
  { variant: 'primary', type: 'button', loading: false, disabled: false },
)
</script>

<template>
  <button
    :type="type"
    :class="['boton', `boton--${variant}`]"
    :disabled="disabled || loading"
    :aria-busy="loading"
  >
    <slot />
  </button>
</template>

<style scoped>
.boton {
  padding: var(--space-2) var(--space-4);
  border: 1px solid transparent;
  border-radius: var(--radius);
  cursor: pointer;
}

.boton[disabled] {
  cursor: not-allowed;
  opacity: 0.6;
}

.boton--primary {
  color: var(--on-accent);
  background: var(--accent-strong);
}

.boton--ghost {
  color: var(--text-strong);
  background: transparent;
  border-color: var(--border);
}
</style>
