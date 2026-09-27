import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetAuth, setAuth, signedIn, signinRedirect, signoutRedirect } from '@/test/fakeAuth.ts'
import { renderRoute } from '@/test/renderRoute.tsx'
import { holdSessionRestore } from '@/test/sessionRestore.ts'

vi.mock('react-oidc-context', () => import('@/test/fakeAuth.ts'))
// The landing's imperative layer needs WebGL and layout, which jsdom lacks.
vi.mock('./landing/mountLanding.ts', () => ({ mountLanding: () => () => {} }))
// The landing's search asks for its cities; these tests don't need any.
vi.mock('@/api/api.ts', async () => {
  const { createApiClient } = await import('@/api/client.ts')
  return { api: createApiClient({ baseUrl: 'http://api.test', getAccessToken: () => undefined, fetch: async () => Response.json([]) }) }
})

beforeEach(resetAuth)

describe('landing', () => {
  it('renders the landing page at /', async () => {
    renderRoute('/')
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('starts the real sign-in from "Sign in", coming back to the app', async () => {
    const user = userEvent.setup()
    renderRoute('/')

    await user.click(await screen.findByRole('link', { name: /sign in/i }))
    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/app' } })
  })

  it('greets a signed-in visitor and links to their account', async () => {
    setAuth(signedIn())
    renderRoute('/')

    expect(await screen.findByRole('link', { name: /asha/i })).toHaveAttribute('href', '/app')
    expect(screen.queryByRole('link', { name: /sign in/i })).not.toBeInTheDocument()
  })

  it('signs a signed-in visitor out', async () => {
    const user = userEvent.setup()
    setAuth(signedIn())
    renderRoute('/')

    await user.click(await screen.findByRole('link', { name: /sign out/i }))
    expect(signoutRedirect).toHaveBeenCalled()
  })

  it('shows neither "Sign in" nor an account while the session is being restored', async () => {
    const restore = holdSessionRestore()
    renderRoute('/')

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /sign in/i })).not.toBeInTheDocument()
    await restore.finish()
    expect(await screen.findByRole('link', { name: /sign in/i })).toBeInTheDocument()
  })
})
