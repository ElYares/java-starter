import { render, screen } from '@testing-library/vue'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import AppButton from './AppButton.vue'

describe('AppButton', () => {
  // La razon de existir del componente. El default de HTML es 'submit', asi que
  // sin esto un boton secundario dentro de un formulario lo envia al hacer clic.
  it('vale type="button" mientras no se pida enviar', () => {
    render(AppButton, { slots: { default: 'Cancelar' } })

    expect(screen.getByRole('button')).toHaveAttribute('type', 'button')
  })

  it('vale type="submit" cuando se pide explicitamente', () => {
    render(AppButton, { props: { type: 'submit' }, slots: { default: 'Entrar' } })

    expect(screen.getByRole('button')).toHaveAttribute('type', 'submit')
  })

  it('marca ocupado y bloquea mientras carga', () => {
    render(AppButton, { props: { loading: true }, slots: { default: 'Entrar' } })

    const boton = screen.getByRole('button')
    expect(boton).toHaveAttribute('aria-busy', 'true')
    expect(boton).toBeDisabled()
  })

  it('no deja pasar el clic cuando carga', async () => {
    const alHacerClic = vi.fn()
    render(AppButton, {
      props: { loading: true },
      attrs: { onClick: alHacerClic },
      slots: { default: 'Entrar' },
    })

    await userEvent.click(screen.getByRole('button'))

    expect(alHacerClic).not.toHaveBeenCalled()
  })

  it('deja pasar el clic cuando esta habilitado', async () => {
    const alHacerClic = vi.fn()
    render(AppButton, { attrs: { onClick: alHacerClic }, slots: { default: 'Entrar' } })

    await userEvent.click(screen.getByRole('button'))

    expect(alHacerClic).toHaveBeenCalledOnce()
  })
})
