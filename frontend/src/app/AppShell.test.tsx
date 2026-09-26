import { cleanup, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ErrorResponse, ErrorTimeout } from 'oidc-client-ts'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetSession } from '@/auth/session.ts'
import { userManager } from '@/auth/userManager.ts'
import { resetAuth, setAuth, signedIn, signedOut, signingIn, signinRedirect, signoutRedirect } from '@/test/fakeAuth.ts'
import { renderRoute } from '@/test/renderRoute.tsx'
import { holdSessionRestore } from '@/test/sessionRestore.ts'

vi.mock('react-oidc-context', () => import('@/test/fakeAuth.ts'))

const meResponse = vi.hoisted(() => ({ respond: (): Response => Response.json({}) }))
vi.mock('@/api/api.ts', async () => {
  const { createApiClient } = await import('@/api/client.ts')
  const { expireSession, renewSession } = await import('@/auth/session.ts')
  return {
    api: createApiClient({
      baseUrl: 'http://api.test',
      getAccessToken: () => 'token',
      fetch: async () => meResponse.respond(),
      reauthenticate: renewSession,
      expireSession,
    }),
  }
})

function meWithRoles(...roles: string[]) {
  meResponse.respond = () => Response.json({ subject: 'user-1', name: 'Asha Rao', email: 'asha@example.com', roles })
}

beforeEach(() => {
  resetAuth()
  meWithRoles('CUSTOMER', 'ORGANIZER')
})

afterEach(() => {
  // Unmount first, so no page re-renders (and refetches) on the reset.
  cleanup()
  resetSession()
  vi.restoreAllMocks()
})

async function navEntries() {
  const nav = await screen.findByRole('navigation', { name: /main/i })
  return within(nav).getAllByRole('link').map((link) => link.textContent)
}

describe('app shell', () => {
  it('shows the name and roles from GET /me when signed in', async () => {
    setAuth(signedIn())
    renderRoute('/app')

    expect(await screen.findByRole('heading', { name: /asha rao/i })).toBeInTheDocument()
    const roles = screen.getByRole('list', { name: /roles/i })
    expect(within(roles).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['Customer', 'Organizer'])
  })

  it('signs out through the identity provider', async () => {
    const user = userEvent.setup()
    setAuth(signedIn())
    renderRoute('/app')

    await user.click(await screen.findByRole('button', { name: /sign out/i }))
    expect(signoutRedirect).toHaveBeenCalled()
  })

  it('says so when /me fails', async () => {
    setAuth(signedIn())
    meResponse.respond = () => new Response(null, { status: 500 })
    renderRoute('/app')

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn.t load your account/i)
  })

  it('sends a signed-out visitor to sign in, coming back here', async () => {
    setAuth(signedOut())
    renderRoute('/app')

    expect(await screen.findByText(/signing you in/i)).toBeInTheDocument()
    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/app' } })
  })

  it('says so when signing in fails, and lets you try again', async () => {
    const user = userEvent.setup()
    setAuth({ ...signedOut(), error: Object.assign(new Error('network'), { source: 'unknown' as const }) })
    renderRoute('/app')

    expect(await screen.findByRole('alert')).toHaveTextContent(/sign-in didn.t work/i)
    expect(signinRedirect).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: /sign in/i }))
    expect(signinRedirect).toHaveBeenCalled()
  })

  it('shows that sign-in is in progress', async () => {
    setAuth(signingIn())
    renderRoute('/app')

    expect(await screen.findByText(/signing you in/i)).toBeInTheDocument()
    expect(signinRedirect).not.toHaveBeenCalled()
  })

  it('waits for the session to be restored instead of sending you to sign in', async () => {
    const restore = holdSessionRestore()
    renderRoute('/app')

    expect(await screen.findByText(/signing you in/i)).toBeInTheDocument()
    expect(signinRedirect).not.toHaveBeenCalled()
    setAuth(signedIn())
    await restore.finish()
    expect(await screen.findByRole('heading', { name: /asha rao/i })).toBeInTheDocument()
  })

  it('switches the whole document between light and dark themes', async () => {
    const user = userEvent.setup()
    setAuth(signedIn())
    renderRoute('/app')

    await user.click(await screen.findByRole('button', { name: /dark theme/i }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')

    await user.click(screen.getByRole('button', { name: /light theme/i }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
  })
})

describe('nav', () => {
  it.each([
    [['CUSTOMER'], ['My account']],
    [['CUSTOMER', 'ORGANIZER'], ['My account', 'Organizer']],
    [['CUSTOMER', 'ADMIN'], ['My account', 'Admin']],
  ])('shows the entries for roles %j', async (roles, entries) => {
    setAuth(signedIn())
    meWithRoles(...roles)
    renderRoute('/app')

    expect(await navEntries()).toEqual(entries)
  })
})

describe('role guards', () => {
  it.each([
    ['/organizer', 'ORGANIZER', /organizer/i],
    ['/admin', 'ADMIN', /admin/i],
  ])('lets a Customer with the role into %s', async (path, role, heading) => {
    setAuth(signedIn())
    meWithRoles('CUSTOMER', role)
    renderRoute(path)

    expect(await screen.findByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
  })

  it.each([
    ['/organizer', ['CUSTOMER']],
    ['/admin', ['CUSTOMER', 'ORGANIZER']],
  ])('shows a signed-in Customer without the role that %s is not allowed', async (path, roles) => {
    setAuth(signedIn())
    meWithRoles(...roles)
    renderRoute(path)

    expect(await screen.findByRole('heading', { name: /not allowed/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to my account/i })).toHaveAttribute('href', '/app')
  })

  it('guards nested pages too', async () => {
    setAuth(signedIn())
    meWithRoles('CUSTOMER')
    renderRoute('/admin/anything')

    expect(await screen.findByRole('heading', { name: /not allowed/i })).toBeInTheDocument()
  })

  it('sends a signed-out visitor to sign in, coming back to the guarded page', async () => {
    setAuth(signedOut())
    renderRoute('/organizer?tab=venues')

    expect(await screen.findByText(/signing you in/i)).toBeInTheDocument()
    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/organizer?tab=venues' } })
  })
})

describe('session expired', () => {
  it('sends you back through sign-in when the API rejects the session and it cannot be renewed', async () => {
    vi.spyOn(userManager, 'signinSilent').mockRejectedValue(new ErrorResponse({ error: 'login_required' }))
    setAuth(signedIn())
    meResponse.respond = () =>
      Response.json({ type: 'urn:getmyseat:problem:unauthorized', status: 401 }, { status: 401 })
    renderRoute('/organizer')

    expect(await screen.findByRole('heading', { name: /session has expired/i })).toBeInTheDocument()
    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/organizer' } })
  })

  it('offers to sign in again if the redirect does not happen', async () => {
    const user = userEvent.setup()
    vi.spyOn(userManager, 'signinSilent').mockRejectedValue(new ErrorResponse({ error: 'login_required' }))
    setAuth(signedIn())
    meResponse.respond = () =>
      Response.json({ type: 'urn:getmyseat:problem:unauthorized', status: 401 }, { status: 401 })
    renderRoute('/organizer')

    await user.click(await screen.findByRole('button', { name: /sign in again/i }))
    expect(signinRedirect).toHaveBeenCalledTimes(2)
  })
})

describe('session renewal', () => {
  it('does not end the session when Keycloak cannot be reached to renew it', async () => {
    vi.spyOn(userManager, 'signinSilent').mockRejectedValue(new ErrorTimeout('IFrame timed out'))
    setAuth(signedIn())
    meResponse.respond = () =>
      Response.json({ type: 'urn:getmyseat:problem:unauthorized', status: 401 }, { status: 401 })
    renderRoute('/app')

    expect(await screen.findByRole('alert')).toHaveTextContent(/can.t reach getmyseat/i)
    expect(screen.queryByRole('heading', { name: /session has expired/i })).not.toBeInTheDocument()
  })
})

describe('API unreachable', () => {
  it('says the API cannot be reached, and retries', async () => {
    const user = userEvent.setup()
    setAuth(signedIn())
    meResponse.respond = () => {
      throw new TypeError('Failed to fetch')
    }
    renderRoute('/app')

    expect(await screen.findByRole('alert')).toHaveTextContent(/can.t reach getmyseat/i)
    meWithRoles('CUSTOMER')
    await user.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByRole('heading', { name: /asha rao/i })).toBeInTheDocument()
  })
})

describe('sign-in callback', () => {
  it('shows that sign-in is in progress while the code is exchanged', async () => {
    setAuth(signingIn())
    renderRoute('/auth/callback')

    expect(await screen.findByText(/signing you in/i)).toBeInTheDocument()
  })

  it('returns to where sign-in started', async () => {
    const auth = signedIn()
    setAuth({ ...auth, user: Object.assign(auth.user!, { state: { returnTo: '/app' } }) })
    const router = renderRoute('/auth/callback')

    expect(await screen.findByRole('heading', { name: /asha rao/i })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app')
  })

  it('ignores a return address that leaves the site', async () => {
    const auth = signedIn()
    setAuth({ ...auth, user: Object.assign(auth.user!, { state: { returnTo: '//evil.example' } }) })
    const router = renderRoute('/auth/callback')

    expect(await screen.findByRole('heading', { name: /asha rao/i })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app')
  })

  it('moves on to the app once signed in', async () => {
    setAuth(signedIn())
    const router = renderRoute('/auth/callback')

    expect(await screen.findByRole('heading', { name: /asha rao/i })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app')
  })

  it('explains a failed sign-in and links back home', async () => {
    setAuth({ ...signedOut(), error: Object.assign(new Error('invalid_grant'), { source: 'signinCallback' as const }) })
    renderRoute('/auth/callback')

    expect(await screen.findByRole('alert')).toHaveTextContent(/sign-in didn.t work/i)
    expect(screen.getByRole('link', { name: /back to getmyseat/i })).toHaveAttribute('href', '/')
  })
})
