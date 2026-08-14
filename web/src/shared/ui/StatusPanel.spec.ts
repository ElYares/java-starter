import { render, screen } from '@testing-library/vue'
import { describe, expect, it } from 'vitest'

import StatusPanel from './StatusPanel.vue'

describe('StatusPanel', () => {
  it('presenta el titulo como encabezado', () => {
    render(StatusPanel, { props: { title: 'No tienes permiso' } })

    expect(screen.getByRole('heading', { name: 'No tienes permiso' })).toBeInTheDocument()
  })

  it('muestra la descripcion cuando la hay', () => {
    render(StatusPanel, {
      props: { title: 'No pudimos conectar', description: 'Intenta de nuevo en un momento.' },
    })

    expect(screen.getByText('Intenta de nuevo en un momento.')).toBeInTheDocument()
  })

  // El arranque en frio usa el panel sin acciones: no hay nada que el usuario
  // pueda hacer mientras se resuelve /me, y un boton muerto seria peor que nada.
  it('no pinta la zona de acciones si nadie llena el slot', () => {
    const { container } = render(StatusPanel, { props: { title: 'Cargando tu sesion' } })

    expect(container.querySelector('.panel__acciones')).toBeNull()
  })

  it('pinta las acciones que le pasen', () => {
    render(StatusPanel, {
      props: { title: 'No pudimos conectar' },
      slots: { default: '<button>Reintentar</button>' },
    })

    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
  })
})
