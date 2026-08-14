<script setup lang="ts">
import { useId } from 'vue'

// El componente existe por el cableado de accesibilidad, no por el borde.
// label/for, aria-invalid y aria-describedby se resuelven una sola vez aqui; si
// cada vista los escribe a mano, el decimoquinto formulario los olvida y el
// error no se anuncia. Escrito asi, `getByLabelText` encuentra el input, que es
// como lo busca tanto un lector de pantalla como una prueba.
withDefaults(
  defineProps<{
    label: string
    modelValue: string
    type?: string
    error?: string
    autocomplete?: string
    required?: boolean
  }>(),
  { type: 'text', required: false },
)

defineEmits<{ 'update:modelValue': [valor: string] }>()

// `useId` de Vue 3.5: id unico y estable, sin contadores propios.
const id = useId()
const idError = `${id}-error`
</script>

<template>
  <div class="campo">
    <label class="campo__etiqueta" :for="id">{{ label }}</label>
    <input
      :id="id"
      class="campo__control"
      :type="type"
      :value="modelValue"
      :autocomplete="autocomplete"
      :required="required"
      :aria-invalid="Boolean(error)"
      :aria-describedby="error ? idError : undefined"
      @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <p v-if="error" :id="idError" class="campo__error">{{ error }}</p>
  </div>
</template>

<style scoped>
.campo {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.campo__etiqueta {
  color: var(--text-strong);
  font-size: 0.875rem;
}

.campo__control {
  font: inherit;
  color: var(--text-strong);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: var(--space-2) var(--space-3);
}

.campo__control[aria-invalid='true'] {
  border-color: var(--danger);
}

.campo__error {
  margin: 0;
  color: var(--danger);
  font-size: 0.875rem;
}
</style>
