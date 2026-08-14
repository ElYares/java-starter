import { render, screen } from '@testing-library/vue'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import App from './App.vue'

// Prueba del arnes, no de la aplicacion. `App.vue` es el placeholder de la fase
// 0 y desaparece en cuanto existan las vistas reales; lo que se verifica aqui
// es que las cinco piezas encajen entre si: el plugin de Vue compilando el SFC,
// jsdom, las consultas de Testing Library, user-event y los matchers de
// jest-dom. Un `expect(true).toBe(true)` habria pasado sin probar ninguna.
describe('arnes de pruebas', () => {
  it('monta un componente y reacciona a un clic', async () => {
    render(App)

    expect(screen.getByRole('heading', { name: 'java-starter' })).toBeInTheDocument()

    const boton = screen.getByRole('button')
    expect(boton).toHaveTextContent('Estado local: 0')

    await userEvent.click(boton)

    expect(boton).toHaveTextContent('Estado local: 1')
  })
})
