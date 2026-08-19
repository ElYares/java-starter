<script setup lang="ts">
// El tono no es solo color: decide como lo anuncia un lector de pantalla.
//
// `role="alert"` es asertivo e interrumpe lo que se este leyendo; `role="status"`
// es cortes y espera turno. Un "credenciales invalidas" tiene que interrumpir,
// porque el usuario esta esperando justo esa respuesta. Un "tu sesion expiro" al
// aterrizar en login, no: llega antes de que el usuario intente nada.
//
// Ambos roles anuncian al aparecer en el DOM, asi que este componente se monta
// con v-if cuando hay algo que decir. Dejarlo montado y vaciar el texto no
// anuncia nada.
withDefaults(defineProps<{ tone?: 'danger' | 'info' }>(), { tone: 'danger' })
</script>

<template>
  <div
    :class="['alerta', `alerta--${tone}`]"
    :role="tone === 'danger' ? 'alert' : 'status'"
  >
    <slot />
  </div>
</template>

<style scoped>
.alerta {
  padding: var(--space-3) var(--space-4);
  border: 1px solid transparent;
  border-radius: var(--radius);
  font-size: 0.9375rem;
}

.alerta--danger {
  color: var(--danger);
  background: var(--danger-bg);
  border-color: var(--danger);
}

.alerta--info {
  color: var(--text-strong);
  background: var(--surface);
  border-color: var(--border);
}
</style>
