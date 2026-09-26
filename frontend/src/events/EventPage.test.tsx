import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetSession } from '@/auth/session.ts'
import { resetAuth, signinRedirect } from '@/test/fakeAuth.ts'
import { renderRoute } from '@/test/renderRoute.tsx'

vi.mock('react-oidc-context', () => import('@/test/fakeAuth.ts'))

// Answers each API path from `responses`; a path with no answer is a 404.
const api = vi.hoisted(() => ({ responses: {} as Record<string, (url: URL) => Response>, requests: [] as Request[] }))
vi.mock('@/api/api.ts', async () => {
  const { createApiClient } = await import('@/api/client.ts')
  const { accessToken } = await import('@/test/fakeAuth.ts')
  return {
    api: createApiClient({
      baseUrl: 'http://api.test',
      getAccessToken: accessToken,
      fetch: async (request) => {
        api.requests.push(request)
        const url = new URL(request.url)
        const respond = api.responses[url.pathname]
        return respond
          ? respond(url)
          : Response.json({ type: 'urn:getmyseat:problem:not-found', status: 404 }, { status: 404 })
      },
    }),
  }
})

const eventPath = '/api/v1/events/event-1'
const showsPath = '/api/v1/events/event-1/shows'

const event = {
  id: 'event-1',
  title: 'Indie Night',
  description: 'Three bands, one long night.',
  category: 'MUSIC',
  language: 'hi',
  status: 'PUBLISHED',
}

function show(id: string, startsAt: string, venue: Record<string, string>) {
  return { id, eventId: 'event-1', startsAt, status: 'PUBLISHED', venue }
}

const blueFrog = { id: 'venue-1', name: 'Blue Frog', address: '3 Mathuradas Mills', city: 'Mumbai', timeZone: 'Asia/Kolkata' }
const roundhouse = { id: 'venue-2', name: 'Roundhouse', address: 'Chalk Farm Road', city: 'London', timeZone: 'Europe/London' }

function page(content: unknown[], number = 0, totalPages = 1, totalElements = content.length) {
  return { content, page: { size: 20, number, totalElements, totalPages } }
}

beforeEach(() => {
  resetAuth()
  api.requests = []
  api.responses = {
    [eventPath]: () => Response.json(event),
    [showsPath]: () =>
      Response.json(
        page([
          show('show-1', '2099-10-03T14:00:00Z', blueFrog),
          show('show-2', '2099-10-10T19:00:00Z', roundhouse),
        ]),
      ),
  }
})

afterEach(resetSession)

describe('event page', () => {
  it('opens for a signed-out visitor without sending them to sign in', async () => {
    renderRoute('/events/event-1')

    expect(await screen.findByRole('heading', { level: 1, name: /indie night/i })).toBeInTheDocument()
    expect(signinRedirect).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument()
    expect(api.requests.every((request) => !request.headers.has('Authorization'))).toBe(true)
  })

  it('shows the Event’s category, language and description', async () => {
    renderRoute('/events/event-1')

    expect(await screen.findByText('Music')).toBeInTheDocument()
    expect(screen.getByText('Hindi')).toBeInTheDocument()
    expect(screen.getByText('Three bands, one long night.')).toBeInTheDocument()
  })

  it('lists its upcoming Shows soonest first, each in its Venue’s time zone and linking to the Show', async () => {
    renderRoute('/events/event-1')

    const shows = await screen.findByRole('list', { name: /shows/i })
    const rows = within(shows).getAllByRole('listitem')
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringMatching(/Sat, 3 Oct, 2099, 7:30 pm IST.*Blue Frog.*Mumbai/),
      expect.stringMatching(/Sat, 10 Oct, 2099, 8:00 pm GMT\+1.*Roundhouse.*London/),
    ])
    expect(within(rows[0]).getByRole('link')).toHaveAttribute('href', '/shows/show-1')
    expect(within(rows[1]).getByRole('link')).toHaveAttribute('href', '/shows/show-2')
  })

  it('has no paging when every Show fits on one page', async () => {
    renderRoute('/events/event-1')

    await screen.findByRole('list', { name: /shows/i })
    expect(screen.queryByRole('navigation', { name: /pages/i })).not.toBeInTheDocument()
  })

  it('pages through an Event with more Shows than fit on one page', async () => {
    const user = userEvent.setup()
    api.responses[showsPath] = (url) =>
      url.searchParams.get('page') === '1'
        ? Response.json(page([show('show-3', '2099-11-01T14:00:00Z', blueFrog)], 1, 2))
        : Response.json(page([show('show-1', '2099-10-03T14:00:00Z', blueFrog)], 0, 2))
    const router = renderRoute('/events/event-1')

    const paging = await screen.findByRole('navigation', { name: /pages/i })
    expect(within(paging).getByText('Page 1 of 2')).toBeInTheDocument()
    expect(within(paging).queryByRole('link', { name: /previous/i })).not.toBeInTheDocument()
    await user.click(within(paging).getByRole('link', { name: /next/i }))

    expect(await screen.findByRole('link', { name: /Sun, 1 Nov, 2099/ })).toHaveAttribute('href', '/shows/show-3')
    expect(router.state.location.search).toBe('?page=2')
    expect(within(paging).getByText('Page 2 of 2')).toBeInTheDocument()
    expect(within(paging).queryByRole('link', { name: /next/i })).not.toBeInTheDocument()

    await user.click(within(paging).getByRole('link', { name: /previous/i }))
    expect(await screen.findByRole('link', { name: /Sat, 3 Oct, 2099/ })).toHaveAttribute('href', '/shows/show-1')
  })

  it('says so when the Event has no upcoming Shows', async () => {
    api.responses[showsPath] = () => Response.json(page([], 0, 0))
    renderRoute('/events/event-1')

    expect(await screen.findByText(/no upcoming shows/i)).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: /indie night/i })).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: /shows/i })).not.toBeInTheDocument()
  })

  it('leads back to the first page from a page past the last', async () => {
    const user = userEvent.setup()
    api.responses[showsPath] = (url) =>
      url.searchParams.get('page') === '0'
        ? Response.json(page([show('show-1', '2099-10-03T14:00:00Z', blueFrog)]))
        : Response.json(page([], 4, 1, 1))
    renderRoute('/events/event-1?page=5')

    expect(await screen.findByText(/no page 5 of shows/i)).toBeInTheDocument()
    expect(screen.queryByText(/no upcoming shows/i)).not.toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: /first page/i }))
    expect(await screen.findByRole('link', { name: /Sat, 3 Oct, 2099/ })).toHaveAttribute('href', '/shows/show-1')
  })

  it('says so when the Event does not exist', async () => {
    delete api.responses[eventPath]
    delete api.responses[showsPath]
    renderRoute('/events/event-1')

    expect(await screen.findByRole('heading', { name: /event not found/i })).toBeInTheDocument()
    expect(signinRedirect).not.toHaveBeenCalled()
  })

  it('says the API cannot be reached, and retries', async () => {
    const user = userEvent.setup()
    api.responses[eventPath] = () => {
      throw new TypeError('Failed to fetch')
    }
    renderRoute('/events/event-1')

    expect(await screen.findByRole('alert')).toHaveTextContent(/can.t reach getmyseat/i)
    api.responses[eventPath] = () => Response.json(event)
    await user.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByRole('heading', { level: 1, name: /indie night/i })).toBeInTheDocument()
  })

  it('says the Shows cannot be loaded, and retries them', async () => {
    const user = userEvent.setup()
    api.responses[showsPath] = () => {
      throw new TypeError('Failed to fetch')
    }
    renderRoute('/events/event-1')

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn.t load the shows/i)
    expect(screen.getByRole('heading', { level: 1, name: /indie night/i })).toBeInTheDocument()
    api.responses[showsPath] = () => Response.json(page([show('show-1', '2099-10-03T14:00:00Z', blueFrog)]))
    await user.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByRole('list', { name: /shows/i })).toBeInTheDocument()
  })
})
