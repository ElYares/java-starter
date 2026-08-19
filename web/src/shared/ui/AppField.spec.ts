import { render, screen } from '@testing-library/vue'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import AppField from './AppField.vue'

describe('AppField', () => {
  // Se busca por la etiqueta, igual que lo hace un lector de pantalla: si el
  // for/id se rompe, la consulta no encuentra nada y el test falla.
  it('conecta la etiqueta con el control', () => {
    render(AppField, { props: { label: 'Correo', modelValue: '' } })

    expect(screen.getByLabelText('Correo')).toBeInTheDocument()
  })

  it('no se declara invalido ni describe nada cuando no hay error', () => {
    render(AppField, { props: { label: 'Correo', modelValue: '' } })

    const control = screen.getByLabelText('Correo')
    expect(control).toHaveAttribute('aria-invalid', 'false')
    expect(control).not.toHaveAttribute('aria-describedby')
  })

  // La asercion fuerte: `toHaveAccessibleDescription` resuelve el
  // aria-describedby de verdad, asi que apuntar a un id inexistente falla aqui
  // aunque el mensaje se vea bien en pantalla.
  it('anuncia el error como descripcion accesible del control', () => {
    render(AppField, {
      props: { label: 'Correo', modelValue: '', error: 'Formato invalido' },
    })

    const control = screen.getByLabelText('Correo')
    expect(control).toHaveAttribute('aria-invalid', 'true')
    expect(control).toHaveAccessibleDescription('Formato invalido')
  })

  it('emite lo que se teclea', async () => {
    const { emitted } = render(AppField, {
      props: { label: 'Correo', modelValue: '' },
    })

    await userEvent.type(screen.getByLabelText('Correo'), 'a')

    expect(emitted()['update:modelValue']).toEqual([['a']])
  })
})
