import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetSession } from '@/auth/session.ts'
import { resetAuth, setAuth, signedIn, signinRedirect } from '@/test/fakeAuth.ts'
import { renderRoute } from '@/test/renderRoute.tsx'

vi.mock('react-oidc-context', () => import('@/test/fakeAuth.ts'))

// Answers each API path from `responses`; a path with no answer is a 404.
const api = vi.hoisted(() => ({ responses: {} as Record<string, () => Response>, requests: [] as Request[] }))
vi.mock('@/api/api.ts', async () => {
  const { createApiClient } = await import('@/api/client.ts')
  const { accessToken } = await import('@/test/fakeAuth.ts')
  return {
    api: createApiClient({
      baseUrl: 'http://api.test',
      getAccessToken: accessToken,
      fetch: async (request) => {
        api.requests.push(request)
        const respond = api.responses[new URL(request.url).pathname]
        return respond
          ? respond()
          : Response.json({ type: 'urn:getmyseat:problem:not-found', status: 404 }, { status: 404 })
      },
    }),
  }
})

const showPath = '/api/v1/shows/show-1'

function show(overrides: Record<string, unknown> = {}) {
  return {
    id: 'show-1',
    eventId: 'event-1',
    startsAt: '2099-10-03T14:00:00Z',
    status: 'PUBLISHED',
    venue: { id: 'venue-1', name: 'Blue Frog', address: '3 Mathuradas Mills', city: 'Mumbai', timeZone: 'Asia/Kolkata' },
    sections: [
      { id: 's-1', name: 'Floor', kind: 'GENERAL_ADMISSION', price: { amountPaise: 50000, currency: 'INR' }, capacity: 200 },
      { id: 's-2', name: 'Balcony', kind: 'SEATED', price: { amountPaise: 149950, currency: 'INR' }, seats: [] },
    ],
    ...overrides,
  }
}

beforeEach(() => {
  resetAuth()
  api.requests = []
  api.responses = {
    [showPath]: () => Response.json(show()),
    '/api/v1/events/event-1': () => Response.json({ id: 'event-1', title: 'Indie Night', status: 'PUBLISHED' }),
    '/api/v1/me': () => Response.json({ subject: 'user-1', name: 'Asha Rao', roles: ['CUSTOMER', 'ORGANIZER'] }),
  }
})

afterEach(resetSession)

describe('show page', () => {
  it('opens for a signed-out visitor without sending them to sign in', async () => {
    renderRoute('/shows/show-1')

    expect(await screen.findByRole('heading', { level: 1, name: /indie night/i })).toBeInTheDocument()
    expect(signinRedirect).not.toHaveBeenCalled()
    expect(screen.queryByRole('navigation', { name: /main/i })).not.toBeInTheDocument()
    expect(api.requests.every((request) => !request.headers.has('Authorization'))).toBe(true)
  })

  it('lets a signed-out visitor sign in, coming back to the Show', async () => {
    const user = userEvent.setup()
    renderRoute('/shows/show-1')

    await user.click(await screen.findByRole('button', { name: /sign in/i }))
    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/shows/show-1' } })
  })

  it('shows a signed-in visitor the same page, with their nav', async () => {
    setAuth(signedIn())
    renderRoute('/shows/show-1')

    expect(await screen.findByRole('heading', { level: 1, name: /indie night/i })).toBeInTheDocument()
    const nav = await screen.findByRole('navigation', { name: /main/i })
    expect(within(nav).getAllByRole('link').map((link) => link.textContent)).toEqual(['My account', 'Organizer'])
    expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument()
  })

  it('links to the Event it belongs to', async () => {
    renderRoute('/shows/show-1')

    expect(await screen.findByRole('link', { name: /indie night/i })).toHaveAttribute('href', '/events/event-1')
  })

  it('shows the Venue and the start time in the Venue’s time zone', async () => {
    renderRoute('/shows/show-1')

    expect(await screen.findByText('Blue Frog')).toBeInTheDocument()
    expect(screen.getByText(/3 Mathuradas Mills, Mumbai/)).toBeInTheDocument()
    expect(screen.getByText('Sat, 3 Oct, 2099, 7:30 pm IST')).toBeInTheDocument()
  })

  it('lists each Section in layout order with its kind and price', async () => {
    renderRoute('/shows/show-1')

    const sections = await screen.findByRole('list', { name: /sections/i })
    const rows = within(sections).getAllByRole('listitem')
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringMatching(/Floor.*General Admission.*₹500$/),
      expect.stringMatching(/Balcony.*Seated.*₹1,499.50$/),
    ])
  })

  it('marks a Show that has already started', async () => {
    api.responses[showPath] = () => Response.json(show({ startsAt: '2020-01-01T14:00:00Z' }))
    renderRoute('/shows/show-1')

    expect(await screen.findByText(/already started/i)).toBeInTheDocument()
    expect(screen.getByRole('list', { name: /sections/i })).toBeInTheDocument()
  })

  it('does not mark an upcoming Show as started', async () => {
    renderRoute('/shows/show-1')

    await screen.findByRole('list', { name: /sections/i })
    expect(screen.queryByText(/already started/i)).not.toBeInTheDocument()
  })

  it('says so when the Show does not exist', async () => {
    delete api.responses[showPath]
    renderRoute('/shows/show-1')

    expect(await screen.findByRole('heading', { name: /show not found/i })).toBeInTheDocument()
    expect(signinRedirect).not.toHaveBeenCalled()
  })

  it('says the API cannot be reached, and retries', async () => {
    const user = userEvent.setup()
    api.responses[showPath] = () => {
      throw new TypeError('Failed to fetch')
    }
    renderRoute('/shows/show-1')

    expect(await screen.findByRole('alert')).toHaveTextContent(/can.t reach getmyseat/i)
    api.responses[showPath] = () => Response.json(show())
    await user.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByRole('list', { name: /sections/i })).toBeInTheDocument()
  })
})
