import { render, screen } from '@testing-library/vue'
import { describe, expect, it } from 'vitest'

import AppAlert from './AppAlert.vue'

describe('AppAlert', () => {
  // El rol decide si un lector de pantalla interrumpe o espera turno. Es la
  // unica diferencia de comportamiento entre los dos tonos, asi que es lo unico
  // que vale la pena afirmar.
  it('interrumpe con el tono de peligro', () => {
    render(AppAlert, { slots: { default: 'Credenciales invalidas' } })

    expect(screen.getByRole('alert')).toHaveTextContent('Credenciales invalidas')
  })

  it('espera turno con el tono informativo', () => {
    render(AppAlert, {
      props: { tone: 'info' },
      slots: { default: 'Tu sesion expiro' },
    })

    expect(screen.getByRole('status')).toHaveTextContent('Tu sesion expiro')
    expect(screen.queryByRole('alert')).toBeNull()
  })
})
