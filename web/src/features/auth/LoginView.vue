<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiError } from '../../shared/api/ApiError'
import AppAlert from '../../shared/ui/AppAlert.vue'
import AppButton from '../../shared/ui/AppButton.vue'
import AppField from '../../shared/ui/AppField.vue'
import { rutaSegura } from '../../app/router/destino'
import { useAuthStore } from '../../app/stores/auth'

const sesion = useAuthStore()
const router = useRouter()
const route = useRoute()

const email = ref('')
const password = ref('')
const enviando = ref(false)

// Dos formas de error y no una. `aviso` es lo que fallo en conjunto —
// credenciales invalidas, demasiados intentos — y `porCampo` es lo que el
// backend marco campo por campo. Mezclarlos obliga a la vista a elegir entre
// mostrar "revisa los campos marcados" sin marcar ninguno, o marcarlos sin
// decir nada.
const aviso = ref('')
const porCampo = ref<Record<string, string>>({})

async function entrar() {
  enviando.value = true
  aviso.value = ''
  porCampo.value = {}

  try {
    await sesion.iniciarSesion(email.value, password.value)

    // El destino se filtra: llega de la query, o sea de fuera. Ver `rutaSegura`.
    await router.replace(rutaSegura(route.query.destino))
  } catch (error) {
    const fallo = ApiError.from(error)

    // El texto sale del `message` del backend, que ya viene redactado para
    // humanos, salvo cuando no hubo servidor: ahi el usuario necesita saber que
    // no fue su contrasena.
    aviso.value = fallo.unavailable
      ? 'No pudimos contactar al servidor. Intenta de nuevo en un momento.'
      : fallo.message
    porCampo.value = { ...fallo.fieldErrors }
  } finally {
    enviando.value = false
  }
}
</script>

<template>
  <section>
    <h1 class="titulo">Iniciar sesion</h1>

    <!-- Montado con v-if y no oculto con CSS: los roles de AppAlert anuncian al
         aparecer en el DOM, asi que vaciar el texto no anunciaria nada. -->
    <AppAlert v-if="aviso" tone="danger">{{ aviso }}</AppAlert>

    <form class="formulario" @submit.prevent="entrar">
      <AppField
        v-model="email"
        label="Email"
        type="email"
        autocomplete="username"
        required
        :error="porCampo.email"
      />

      <AppField
        v-model="password"
        label="Contrasena"
        type="password"
        autocomplete="current-password"
        required
        :error="porCampo.password"
      />

      <AppButton type="submit" :loading="enviando">
        {{ enviando ? 'Entrando...' : 'Entrar' }}
      </AppButton>
    </form>
  </section>
</template>

<style scoped>
.titulo {
  margin: 0 0 var(--space-4);
  font-size: 1.5rem;
}

.formulario {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  margin-top: var(--space-4);
}
</style>
