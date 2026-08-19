import { render, screen } from '@testing-library/vue'
import userEvent from '@testing-library/user-event'
import { createPinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import type { Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { crearRouter } from '../../app/router'
import { api } from '../../shared/api/client'
import { servidorFalso } from '../../test/api'
import LoginView from './LoginView.vue'

const PERFIL = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'admin@java-starter.localhost',
  displayName: 'Admin',
  roles: ['ADMIN'],
}

/**
 * Monta la vista con el router ya posado en `/login`.
 *
 * La vista lee `route.query.destino`, asi que necesita una ruta resuelta: sin el
 * `push` previo el router esta en su localizacion inicial y la query esta vacia.
 */
async function montar(query = '') {
  const router: Router = crearRouter(createMemoryHistory())
  await router.push(`/login${query}`)

  render(LoginView, { global: { plugins: [createPinia(), router] } })

  return router
}

async function rellenarYEnviar() {
  await userEvent.type(screen.getByLabelText('Email'), 'admin@java-starter.localhost')
  await userEvent.type(screen.getByLabelText('Contrasena'), 'cambiame')
  await userEvent.click(screen.getByRole('button', { name: 'Entrar' }))
}

describe('LoginView', () => {
  const original = api.defaults.adapter

  beforeEach(() => {
    api.defaults.adapter = original
  })

  afterEach(() => {
    api.defaults.adapter = original
  })

  it('aterriza en el destino pedido y no en la raiz', async () => {
    api.defaults.adapter = servidorFalso({
      '/auth/login': { status: 204 },
      '/auth/me': { status: 200, data: PERFIL },
    }).adapter

    const router = await montar('?destino=%2Fdashboard%3Ftab%3Duploads')
    await rellenarYEnviar()

    // `userEvent.click` espera al DOM, no al trabajo asincrono del handler: hay
    // dos peticiones y una navegacion entre el clic y el aterrizaje.
    await vi.waitFor(() =>
      expect(router.currentRoute.value.fullPath).toBe('/dashboard?tab=uploads'),
    )
  })

  // El aviso sale del `message` del backend, que ya viene redactado para
  // humanos. La vista no arma su propio texto para esto.
  it('muestra lo que dijo el backend cuando rechaza las credenciales', async () => {
    api.defaults.adapter = servidorFalso({
      '/auth/login': {
        status: 401,
        data: { code: 'UNAUTHENTICATED', detail: 'Credenciales invalidas' },
      },
    }).adapter

    await montar()
    await rellenarYEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent('Credenciales invalidas')
  })

  // `fieldErrors` va directo al prop `error` de AppField, que es quien cablea
  // aria-invalid y aria-describedby.
  it('marca el campo que el backend senalo', async () => {
    api.defaults.adapter = servidorFalso({
      '/auth/login': {
        status: 400,
        data: {
          code: 'VALIDATION_FAILED',
          detail: 'Revisa los campos marcados',
          errors: [{ field: 'email', code: 'Email', message: 'El email no tiene formato' }],
        },
      },
    }).adapter

    await montar()
    await rellenarYEnviar()

    const email = await screen.findByLabelText('Email')
    expect(email).toHaveAttribute('aria-invalid', 'true')
    expect(email).toHaveAccessibleDescription('El email no tiene formato')
  })

  // Sin servidor el usuario necesita saber que no fue su contrasena. El mensaje
  // generico de `ApiError` diria "no se pudo contactar al servidor", que es
  // correcto pero no dice que sus credenciales no estan en duda.
  it('no culpa a las credenciales cuando no hubo servidor', async () => {
    api.defaults.adapter = servidorFalso({ '/auth/login': { status: 0 } }).adapter

    await montar()
    await rellenarYEnviar()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'No pudimos contactar al servidor',
    )
  })
})
