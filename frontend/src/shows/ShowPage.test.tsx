import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetSession } from '@/auth/session.ts'
import { resetAuth, setAuth, signedIn, signinRedirect } from '@/test/fakeAuth.ts'
import { renderRoute } from '@/test/renderRoute.tsx'

vi.mock('react-oidc-context', () => import('@/test/fakeAuth.ts'))

// Answers each API path from `responses`; a path with no answer is a 404.
const api = vi.hoisted(() => ({ responses: {} as Record<string, () => Response | Promise<Response>>, requests: [] as Request[] }))
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
const availabilityPath = '/api/v1/shows/show-1/availability'

function show(overrides: Record<string, unknown> = {}) {
  return {
    id: 'show-1',
    eventId: 'event-1',
    startsAt: '2099-10-03T14:00:00Z',
    status: 'PUBLISHED',
    venue: { id: 'venue-1', name: 'Blue Frog', address: '3 Mathuradas Mills', city: 'Mumbai', timeZone: 'Asia/Kolkata' },
    sections: [
      { id: 's-1', name: 'Floor', kind: 'GENERAL_ADMISSION', price: { amountPaise: 50000, currency: 'INR' }, capacity: 200 },
      {
        id: 's-2',
        name: 'Balcony',
        kind: 'SEATED',
        price: { amountPaise: 149950, currency: 'INR' },
        seats: [
          { id: 'a2', row: 'A', number: 2, label: 'A2' },
          { id: 'a1', row: 'A', number: 1, label: 'A1' },
          { id: 'b1', row: 'B', number: 1, label: 'B1' },
        ],
      },
    ],
    ...overrides,
  }
}

function availability({ floor = 37, a1 = true, a2 = false, b1 = true } = {}) {
  return {
    showId: 'show-1',
    sections: [
      { id: 's-1', kind: 'GENERAL_ADMISSION', capacity: 200, available: floor },
      {
        id: 's-2',
        kind: 'SEATED',
        seats: [
          { id: 'a1', available: a1 },
          { id: 'a2', available: a2 },
          { id: 'b1', available: b1 },
        ],
      },
    ],
  }
}

const availabilityRequests = () => api.requests.filter((request) => new URL(request.url).pathname === availabilityPath)

beforeEach(() => {
  resetAuth()
  api.requests = []
  api.responses = {
    [showPath]: () => Response.json(show()),
    [availabilityPath]: () => Response.json(availability()),
    '/api/v1/events/event-1': () => Response.json({ id: 'event-1', title: 'Indie Night', status: 'PUBLISHED' }),
    '/api/v1/me': () => Response.json({ subject: 'user-1', name: 'Asha Rao', roles: ['CUSTOMER', 'ORGANIZER'] }),
  }
})

afterEach(() => {
  resetSession()
  vi.useRealTimers()
})

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

describe('seat map', () => {
  it('draws a stage and every Section in layout order', async () => {
    renderRoute('/shows/show-1')

    const map = await screen.findByRole('region', { name: /seat map/i })
    expect(within(map).getByText(/stage/i)).toBeInTheDocument()
    expect(within(map).getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)).toEqual([
      'Floor',
      'Balcony',
    ])
  })

  it('shows each Seat by row, sorted by number, as available or held', async () => {
    renderRoute('/shows/show-1')

    const balcony = await screen.findByRole('group', { name: 'Balcony' })
    await within(balcony).findByRole('checkbox', { name: 'Balcony, row A, seat 1, available' })
    expect(within(balcony).getAllByRole('checkbox').map((seat) => seat.getAttribute('aria-label'))).toEqual([
      'Balcony, row A, seat 1, available',
      'Balcony, row A, seat 2, held by someone else',
      'Balcony, row B, seat 1, available',
    ])
  })

  it('lets the keyboard reach every Seat', async () => {
    const user = userEvent.setup()
    renderRoute('/shows/show-1')

    const first = await screen.findByRole('checkbox', { name: /row A, seat 1/ })
    first.focus()
    await user.tab()
    expect(screen.getByRole('checkbox', { name: /row A, seat 2/ })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('checkbox', { name: /row B, seat 1/ })).toHaveFocus()
  })

  it('shows how many General Admission places are left', async () => {
    renderRoute('/shows/show-1')

    const floor = await screen.findByRole('group', { name: 'Floor' })
    expect(await within(floor).findByText('37 of 200 places left')).toBeInTheDocument()
  })

  it('says a General Admission Section is sold out at zero', async () => {
    api.responses[availabilityPath] = () => Response.json(availability({ floor: 0 }))
    renderRoute('/shows/show-1')

    const floor = await screen.findByRole('group', { name: 'Floor' })
    expect(await within(floor).findByText('Sold out')).toBeInTheDocument()
  })

  it('draws the layout while availability is still loading', async () => {
    api.responses[availabilityPath] = () => new Promise<Response>(() => {})
    renderRoute('/shows/show-1')

    expect(await screen.findByText(/checking what.s left/i)).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Balcony, row A, seat 1, not known yet' })).toBeInTheDocument()
  })

  it('keeps the rest of the Show page when availability fails', async () => {
    api.responses[availabilityPath] = () => Response.json({ type: 'urn:getmyseat:problem:internal-error', status: 500 }, { status: 500 })
    renderRoute('/shows/show-1')

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn.t load what.s left/i)
    expect(screen.getByRole('heading', { level: 1, name: /indie night/i })).toBeInTheDocument()
    expect(screen.getByRole('list', { name: /sections/i })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Balcony, row A, seat 1, not known yet' })).toBeInTheDocument()
  })

  it('refreshes availability every 15 seconds without losing its place', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderRoute('/shows/show-1')

    const seat = await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, available' })
    seat.focus()
    expect(availabilityRequests()).toHaveLength(1)

    api.responses[availabilityPath] = () => Response.json(availability({ a1: false, floor: 36 }))
    await act(() => vi.advanceTimersByTimeAsync(15_000))

    expect(availabilityRequests()).toHaveLength(2)
    expect(await screen.findByText('36 of 200 places left')).toBeInTheDocument()
    expect(seat).toHaveAccessibleName('Balcony, row A, seat 1, held by someone else')
    expect(seat).toHaveFocus()
  })

  it('keeps the last availability when a refresh fails', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderRoute('/shows/show-1')

    await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, available' })
    api.responses[availabilityPath] = () => Response.json({ type: 'urn:getmyseat:problem:internal-error', status: 500 }, { status: 500 })
    await act(() => vi.advanceTimersByTimeAsync(15_000))

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn.t refresh what.s left/i)
    expect(screen.getByRole('checkbox', { name: 'Balcony, row A, seat 1, available' })).toBeInTheDocument()
  })

  it('stops refreshing, and says sales are over, once the Show has started', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    api.responses[showPath] = () => Response.json(show({ startsAt: '2020-01-01T14:00:00Z' }))
    renderRoute('/shows/show-1')

    expect(await screen.findByText(/no longer on sale/i)).toBeInTheDocument()
    await screen.findByRole('img', { name: 'Balcony, row A, seat 1, available' })
    await act(() => vi.advanceTimersByTimeAsync(60_000))
    expect(availabilityRequests()).toHaveLength(1)
  })

  it('does not ask for availability of a draft Show', async () => {
    api.responses[showPath] = () => Response.json(show({ status: 'DRAFT' }))
    renderRoute('/shows/show-1')

    expect(await screen.findByText(/once the show is published/i)).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Balcony, row A, seat 1, not known yet' })).toBeInTheDocument()
    expect(availabilityRequests()).toHaveLength(0)
  })
})

describe('selecting tickets', () => {
  const summary = () => screen.getByRole('region', { name: /your selection/i })
  const summaryLines = () => within(within(summary()).getByRole('list')).getAllByRole('listitem').map((line) => line.textContent)

  it('selects an available Seat on a click, and lets it go on another', async () => {
    const user = userEvent.setup()
    renderRoute('/shows/show-1')

    const seat = await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, available' })
    await user.click(seat)
    expect(seat).toBeChecked()
    expect(seat).toHaveAccessibleName('Balcony, row A, seat 1, selected')

    await user.click(seat)
    expect(seat).not.toBeChecked()
    expect(seat).toHaveAccessibleName('Balcony, row A, seat 1, available')
  })

  it('selects a Seat from the keyboard', async () => {
    const user = userEvent.setup()
    renderRoute('/shows/show-1')

    const seat = await screen.findByRole('checkbox', { name: /row A, seat 1, available/ })
    seat.focus()
    await user.keyboard(' ')
    expect(seat).toBeChecked()
    await user.keyboard('{Enter}')
    expect(seat).not.toBeChecked()
  })

  it('does not select a Seat held by someone else', async () => {
    const user = userEvent.setup()
    renderRoute('/shows/show-1')

    const held = await screen.findByRole('checkbox', { name: /row A, seat 2, held by someone else/ })
    expect(held).toHaveAttribute('aria-disabled', 'true')
    await user.click(held)
    expect(held).not.toBeChecked()
  })

  it('steps General Admission places up to what is left', async () => {
    const user = userEvent.setup()
    api.responses[availabilityPath] = () => Response.json(availability({ floor: 2 }))
    renderRoute('/shows/show-1')

    const more = await screen.findByRole('button', { name: 'More Floor tickets' })
    await user.click(more)
    await user.click(more)
    expect(within(screen.getByRole('group', { name: 'Floor' })).getByRole('status')).toHaveTextContent('2')
    expect(more).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Fewer Floor tickets' }))
    expect(within(screen.getByRole('group', { name: 'Floor' })).getByRole('status')).toHaveTextContent('1')
  })

  it('lists each pick with its Section Price, and the total in ₹', async () => {
    const user = userEvent.setup()
    renderRoute('/shows/show-1')

    await user.click(await screen.findByRole('checkbox', { name: /row B, seat 1, available/ }))
    await user.click(screen.getByRole('button', { name: 'More Floor tickets' }))
    await user.click(screen.getByRole('button', { name: 'More Floor tickets' }))

    expect(summaryLines()).toEqual([
      expect.stringMatching(/Floor.*General Admission × 2.*₹500 each.*₹1,000/),
      expect.stringMatching(/Balcony.*Row B, seat 1.*₹1,499.50/),
    ])
    expect(within(summary()).getByText(/total/i).parentElement).toHaveTextContent('₹2,499.50')
  })

  it('enables Hold only once something is picked', async () => {
    const user = userEvent.setup()
    renderRoute('/shows/show-1')

    await screen.findByRole('checkbox', { name: /row A, seat 1, available/ })
    const hold = screen.getByRole('button', { name: 'Hold' })
    expect(hold).toBeDisabled()

    await user.click(screen.getByRole('checkbox', { name: /row A, seat 1/ }))
    expect(hold).toBeEnabled()
  })

  it('stops at 10 tickets and says the limit is reached', async () => {
    const user = userEvent.setup()
    renderRoute('/shows/show-1')

    const more = await screen.findByRole('button', { name: 'More Floor tickets' })
    for (let i = 0; i < 10; i++) await user.click(more)
    expect(more).toBeDisabled()
    expect(within(summary()).getByText(/limit of 10 tickets/i)).toBeInTheDocument()

    const seat = screen.getByRole('checkbox', { name: /row A, seat 1, available/ })
    expect(seat).toHaveAttribute('aria-disabled', 'true')
    await user.click(seat)
    expect(seat).not.toBeChecked()
    expect(screen.getByRole('button', { name: 'Hold' })).toBeEnabled()
  })

  it('drops picks the next refresh shows as gone', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderRoute('/shows/show-1')

    await user.click(await screen.findByRole('checkbox', { name: /row A, seat 1, available/ }))
    await user.click(screen.getByRole('checkbox', { name: /row B, seat 1, available/ }))
    for (let i = 0; i < 3; i++) await user.click(screen.getByRole('button', { name: 'More Floor tickets' }))

    api.responses[availabilityPath] = () => Response.json(availability({ a1: false, floor: 1 }))
    await act(() => vi.advanceTimersByTimeAsync(15_000))

    expect(await screen.findByText('1 of 200 places left')).toBeInTheDocument()
    expect(summaryLines()).toEqual([
      expect.stringMatching(/Floor.*General Admission × 1/),
      expect.stringMatching(/Balcony.*Row B, seat 1/),
    ])
    expect(screen.getByRole('checkbox', { name: /row A, seat 1, held by someone else/ })).not.toBeChecked()
  })

  it('has nothing to pick once the Show has started', async () => {
    api.responses[showPath] = () => Response.json(show({ startsAt: '2020-01-01T14:00:00Z' }))
    renderRoute('/shows/show-1')

    await screen.findByRole('img', { name: /row A, seat 1, available/ })
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /more floor tickets/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /your selection/i })).not.toBeInTheDocument()
  })
})
