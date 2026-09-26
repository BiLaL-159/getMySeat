import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { routes } from './routes.tsx'

// The landing's imperative layer needs WebGL and layout, which jsdom lacks.
vi.mock('./landing/mountLanding.ts', () => ({ mountLanding: () => () => {} }))

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(<RouterProvider router={router} />)
}

describe('routes', () => {
  it('renders the landing page at /', async () => {
    renderAt('/')
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /component preview/i })).not.toBeInTheDocument()
  })

  it('renders the shadcn primitives on the app route', async () => {
    renderAt('/app')
    expect(await screen.findByRole('heading', { name: /component preview/i })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /email/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /hold seats/i })).toBeInTheDocument()
    expect(screen.getByText(/tonight/i, { selector: '[data-slot="card-title"]' })).toBeInTheDocument()
  })

  it('switches the whole document between light and dark themes', async () => {
    const user = userEvent.setup()
    renderAt('/app')

    await user.click(await screen.findByRole('button', { name: /dark theme/i }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')

    await user.click(screen.getByRole('button', { name: /light theme/i }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
  })
})
