import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { act } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetSession } from '@/auth/session.ts'
import { resetAuth, setAuth, signedIn, signedOut, signinRedirect } from '@/test/fakeAuth.ts'
import { renderRoute } from '@/test/renderRoute.tsx'
import { keepSelection, keptSelection } from './keptSelection.ts'

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

const holdsPath = '/api/v1/shows/show-1/holds'
const myHoldPath = '/api/v1/shows/show-1/holds/mine'

// A Hold of Balcony A1 and two Floor places.
function hold(overrides: Record<string, unknown> = {}) {
  return {
    id: 'hold-1',
    showId: 'show-1',
    status: 'ACTIVE',
    expiresAt: '2099-10-03T13:10:00Z',
    items: [
      { kind: 'GENERAL_ADMISSION', sectionId: 's-1', quantity: 2, pricePaise: 50000 },
      { kind: 'SEAT', sectionId: 's-2', seatId: 'a1', rowLabel: 'A', seatNumber: 1, quantity: 1, pricePaise: 149950 },
    ],
    totalPaise: 249950,
    currency: 'INR',
    createdAt: '2099-10-03T13:00:00Z',
    ...overrides,
  }
}

const problemResponse = (type = 'conflict', extra: object = {}) =>
  Response.json({ type: `urn:getmyseat:problem:${type}`, status: 409, ...extra }, { status: 409 })

const availabilityRequests = () => api.requests.filter((request) => new URL(request.url).pathname === availabilityPath)

beforeEach(() => {
  resetAuth()
  sessionStorage.clear()
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

describe('holding tickets', () => {
  const holdRequests = () => api.requests.filter((request) => request.method === 'POST' && new URL(request.url).pathname === holdsPath)
  const keysSent = () => holdRequests().map((request) => request.headers.get('Idempotency-Key'))
  const summary = () => screen.getByRole('region', { name: /your selection/i })

  beforeEach(() => setAuth(signedIn()))

  async function pickA1AndTwoFloor(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('checkbox', { name: /row A, seat 1, available/ }))
    await user.click(screen.getByRole('button', { name: 'More Floor tickets' }))
    await user.click(screen.getByRole('button', { name: 'More Floor tickets' }))
  }

  it('holds the selection under an Idempotency-Key and shows the Hold', async () => {
    const user = userEvent.setup()
    api.responses[holdsPath] = () => {
      api.responses[availabilityPath] = () => Response.json(availability({ a1: false, floor: 35 }))
      return Response.json(hold(), { status: 201 })
    }
    renderRoute('/shows/show-1')

    await pickA1AndTwoFloor(user)
    await user.click(screen.getByRole('button', { name: 'Hold' }))

    const panel = await screen.findByRole('region', { name: /your hold/i })
    const [request] = holdRequests()
    expect(request.headers.get('Idempotency-Key')).toMatch(/^[\w-]{8,}$/)
    expect(await request.json()).toEqual({ seats: ['a1'], generalAdmission: [{ sectionId: 's-1', quantity: 2 }] })

    expect(within(within(panel).getByRole('list')).getAllByRole('listitem').map((line) => line.textContent)).toEqual([
      expect.stringMatching(/Floor.*General Admission × 2.*₹500 each.*₹1,000/),
      expect.stringMatching(/Balcony.*Row A, seat 1.*₹1,499.50/),
    ])
    expect(within(panel).getByText(/total/i).parentElement).toHaveTextContent('₹2,499.50')
    const pay = within(panel).getByRole('button', { name: 'Pay' })
    expect(pay).toBeDisabled()
    expect(pay).toHaveAccessibleDescription('Payments arrive soon')

    expect(await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, in my Hold' })).not.toBeChecked()
    expect(within(summary()).queryByRole('list')).not.toBeInTheDocument()
  })

  it('retries a plain conflict on its own, with the same key', async () => {
    const user = userEvent.setup()
    const answers = [problemResponse(), Response.json(hold(), { status: 201 })]
    api.responses[holdsPath] = () => answers.shift()!
    renderRoute('/shows/show-1')

    await pickA1AndTwoFloor(user)
    await user.click(screen.getByRole('button', { name: 'Hold' }))

    expect(await screen.findByRole('region', { name: /your hold/i })).toBeInTheDocument()
    expect(keysSent()).toHaveLength(2)
    expect(keysSent()[1]).toBe(keysSent()[0])
  })

  it('reuses the key when trying the same selection again, and not once it changes', async () => {
    const user = userEvent.setup()
    api.responses[holdsPath] = () => Response.json({ type: 'urn:getmyseat:problem:not-found', status: 404 }, { status: 404 })
    renderRoute('/shows/show-1')

    await pickA1AndTwoFloor(user)
    await user.click(screen.getByRole('button', { name: 'Hold' }))
    expect(await within(summary()).findByRole('alert')).toHaveTextContent(/couldn.t hold these tickets/i)
    await user.click(screen.getByRole('button', { name: 'Hold' }))
    await waitFor(() => expect(keysSent()).toHaveLength(2))
    expect(keysSent()[1]).toBe(keysSent()[0])

    await user.click(screen.getByRole('checkbox', { name: /row B, seat 1, available/ }))
    await user.click(screen.getByRole('button', { name: 'Hold' }))
    await waitFor(() => expect(keysSent()).toHaveLength(3))
    expect(keysSent()[2]).not.toBe(keysSent()[0])
  })

  it('marks only what was lost, keeps the rest, says what went, and asks what is left', async () => {
    const user = userEvent.setup()
    api.responses[holdsPath] = () => {
      api.responses[availabilityPath] = () => Response.json(availability({ a1: false, floor: 1 }))
      return problemResponse('inventory-unavailable', { unavailableSeats: ['a1'], unavailableSections: [{ sectionId: 's-1', available: 1 }] })
    }
    renderRoute('/shows/show-1')

    await pickA1AndTwoFloor(user)
    await user.click(screen.getByRole('checkbox', { name: /row B, seat 1, available/ }))
    const asked = availabilityRequests().length
    await user.click(screen.getByRole('button', { name: 'Hold' }))

    const alert = await within(summary()).findByRole('alert')
    expect(alert).toHaveTextContent(/someone else got there first/i)
    expect(within(alert).getAllByRole('listitem').map((line) => line.textContent)).toEqual([
      'Floor: only 1 place left',
      'Balcony, row A, seat 1',
    ])
    expect(holdRequests()).toHaveLength(1)

    const lost = screen.getByRole('checkbox', { name: 'Balcony, row A, seat 1, taken before you could hold it' })
    expect(lost).not.toBeChecked()
    expect(lost).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByRole('checkbox', { name: /row B, seat 1, selected/ })).toBeChecked()
    expect(within(screen.getByRole('group', { name: 'Floor' })).getByRole('status')).toHaveTextContent('1')
    await waitFor(() => expect(availabilityRequests().length).toBeGreaterThan(asked))
  })

  it('marks a General Admission shortfall on the map', async () => {
    const user = userEvent.setup()
    api.responses[holdsPath] = () => problemResponse('inventory-unavailable', { unavailableSeats: [], unavailableSections: [{ sectionId: 's-1', available: 1 }] })
    renderRoute('/shows/show-1')

    await pickA1AndTwoFloor(user)
    await user.click(screen.getByRole('button', { name: 'Hold' }))

    const floor = screen.getByRole('group', { name: 'Floor' })
    expect(await within(floor).findByText(/someone else got there first/i)).toHaveTextContent(/only 1 place left/i)
    expect(screen.queryByText(/taken before you could hold it/i)).not.toBeInTheDocument()
  })

  it('lets a lost Seat be picked again once it is free', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    api.responses[holdsPath] = () => {
      api.responses[availabilityPath] = () => Response.json(availability({ a1: false }))
      return problemResponse('inventory-unavailable', { unavailableSeats: ['a1'], unavailableSections: [] })
    }
    renderRoute('/shows/show-1')

    await user.click(await screen.findByRole('checkbox', { name: /row A, seat 1, available/ }))
    await user.click(screen.getByRole('button', { name: 'Hold' }))
    await screen.findByRole('checkbox', { name: /row A, seat 1, taken before you could hold it/ })

    api.responses[availabilityPath] = () => Response.json(availability({ a1: true }))
    await act(() => vi.advanceTimersByTimeAsync(15_000))

    const seat = await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, available' })
    await user.click(seat)
    expect(seat).toBeChecked()
  })

  it('does not say something was lost when a race keeps failing', async () => {
    const user = userEvent.setup()
    api.responses[holdsPath] = () => problemResponse('inventory-unavailable', { unavailableSeats: [], unavailableSections: [] })
    renderRoute('/shows/show-1')

    await pickA1AndTwoFloor(user)
    await user.click(screen.getByRole('button', { name: 'Hold' }))

    expect(await within(summary()).findByRole('alert', {}, { timeout: 4000 })).toHaveTextContent(/couldn.t hold these tickets/i)
    expect(holdRequests()).toHaveLength(4)
    expect(screen.queryByText(/someone else got there first/i)).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /row A, seat 1, selected/ })).toBeChecked()
  })

  it('does not retry a key used for other tickets, and tries again with a new one', async () => {
    const user = userEvent.setup()
    api.responses[holdsPath] = () => problemResponse('idempotency-key-reused')
    renderRoute('/shows/show-1')

    await pickA1AndTwoFloor(user)
    await user.click(screen.getByRole('button', { name: 'Hold' }))
    await within(summary()).findByRole('alert')
    expect(holdRequests()).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Hold' }))
    await waitFor(() => expect(keysSent()).toHaveLength(2))
    expect(keysSent()[1]).not.toBe(keysSent()[0])
  })

  it('keeps the selection, and says so, when the key brings back a Hold that has ended', async () => {
    const user = userEvent.setup()
    api.responses[holdsPath] = () => Response.json(hold({ status: 'RELEASED' }), { status: 201 })
    renderRoute('/shows/show-1')

    await pickA1AndTwoFloor(user)
    await user.click(screen.getByRole('button', { name: 'Hold' }))

    expect(await within(summary()).findByRole('alert')).toHaveTextContent(/has already ended/i)
    expect(screen.queryByRole('region', { name: /your hold/i })).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /row A, seat 1, selected/ })).toBeChecked()

    await user.click(screen.getByRole('button', { name: 'Hold' }))
    await waitFor(() => expect(keysSent()).toHaveLength(2))
    expect(keysSent()[1]).not.toBe(keysSent()[0])
  })

  it('keeps picks made while the Hold was on its way', async () => {
    const user = userEvent.setup()
    let answer!: (response: Response) => void
    api.responses[holdsPath] = () => new Promise<Response>((resolve) => (answer = resolve))
    renderRoute('/shows/show-1')

    await user.click(await screen.findByRole('checkbox', { name: /row A, seat 1, available/ }))
    await user.click(screen.getByRole('button', { name: 'Hold' }))
    await waitFor(() => expect(holdRequests()).toHaveLength(1))
    await user.click(screen.getByRole('checkbox', { name: /row B, seat 1, available/ }))
    answer(Response.json(hold({ items: [hold().items[1]], totalPaise: 149950 }), { status: 201 }))

    await screen.findByRole('region', { name: /your hold/i })
    expect(screen.getByRole('checkbox', { name: /row B, seat 1, selected/ })).toBeChecked()
  })

  it('brings the Hold back after a reload', async () => {
    api.responses[myHoldPath] = () => Response.json(hold())
    renderRoute('/shows/show-1')

    const panel = await screen.findByRole('region', { name: /your hold/i })
    expect(within(panel).getByText(/total/i).parentElement).toHaveTextContent('₹2,499.50')
    expect(screen.getByRole('checkbox', { name: 'Balcony, row A, seat 1, in my Hold' })).toBeInTheDocument()
  })

  it('shows no Hold when there is none', async () => {
    renderRoute('/shows/show-1')

    await screen.findByRole('checkbox', { name: /row A, seat 1, available/ })
    await waitFor(() => expect(api.requests.some((request) => new URL(request.url).pathname === myHoldPath)).toBe(true))
    expect(screen.queryByRole('region', { name: /your hold/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('asks a signed-out visitor to sign in instead of holding', async () => {
    const user = userEvent.setup()
    setAuth(signedOut())
    renderRoute('/shows/show-1')

    await user.click(await screen.findByRole('checkbox', { name: /row A, seat 1, available/ }))
    await user.click(screen.getByRole('button', { name: 'Hold' }))

    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/shows/show-1' } })
    expect(holdRequests()).toHaveLength(0)
    expect(api.requests.some((request) => new URL(request.url).pathname === myHoldPath)).toBe(false)
  })
})

describe('signing in to hold', () => {
  const holdRequests = () => api.requests.filter((request) => request.method === 'POST' && new URL(request.url).pathname === holdsPath)
  const summary = () => screen.getByRole('region', { name: /your selection/i })
  // The picks come first; a note about what went may follow with a list of its own.
  const summaryLines = () => within(within(summary()).getAllByRole('list')[0]).getAllByRole('listitem').map((line) => line.textContent)

  // A signed-out visitor picks Balcony A1 and two Floor places, clicks "Hold", and comes back signed in.
  async function signInToHold() {
    const user = userEvent.setup()
    renderRoute('/shows/show-1')
    await user.click(await screen.findByRole('checkbox', { name: /row A, seat 1, available/ }))
    await user.click(screen.getByRole('button', { name: 'More Floor tickets' }))
    await user.click(screen.getByRole('button', { name: 'More Floor tickets' }))
    await user.click(screen.getByRole('button', { name: 'Hold' }))
    expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/shows/show-1' } })

    cleanup()
    setAuth(signedIn())
    renderRoute('/shows/show-1')
    return user
  }

  it('comes back to the selection, says it was kept, and holds it on one click', async () => {
    api.responses[holdsPath] = () => Response.json(hold(), { status: 201 })
    const user = await signInToHold()

    expect(await screen.findByRole('checkbox', { name: /row A, seat 1, selected/ })).toBeChecked()
    expect(summaryLines()).toEqual([
      expect.stringMatching(/Floor.*General Admission × 2/),
      expect.stringMatching(/Balcony.*Row A, seat 1/),
    ])
    expect(within(summary()).getByText(/kept the tickets you picked/i)).toBeInTheDocument()
    expect(holdRequests()).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Hold' }))
    expect(await screen.findByRole('region', { name: /your hold/i })).toBeInTheDocument()
    expect(holdRequests()).toHaveLength(1)
    expect(await holdRequests()[0].json()).toEqual({ seats: ['a1'], generalAdmission: [{ sectionId: 's-1', quantity: 2 }] })
  })

  it('drops picks that went while the visitor was away, and says what went', async () => {
    const user = userEvent.setup()
    keepSelection('show-1', { seats: ['a1', 'b1'], generalAdmission: { 's-1': 3 } })
    api.responses[availabilityPath] = () => Response.json(availability({ a1: false, floor: 1 }))
    setAuth(signedIn())
    renderRoute('/shows/show-1')

    const note = await within(await screen.findByRole('region', { name: /your selection/i })).findByText(/went while you were away/i)
    expect(within(note.parentElement!).getAllByRole('listitem').map((line) => line.textContent)).toEqual([
      'Floor: only 1 place left',
      'Balcony, row A, seat 1',
    ])
    expect(summaryLines()).toEqual([
      expect.stringMatching(/Floor.*General Admission × 1/),
      expect.stringMatching(/Balcony.*Row B, seat 1/),
    ])
    expect(screen.getByRole('checkbox', { name: /row A, seat 1, held by someone else/ })).not.toBeChecked()
    await user.click(screen.getByRole('button', { name: 'Hold' }))
    await waitFor(() => expect(holdRequests()).toHaveLength(1))
  })

  it('says so when everything picked went while the visitor was away', async () => {
    keepSelection('show-1', { seats: ['a1'], generalAdmission: {} })
    api.responses[availabilityPath] = () => Response.json(availability({ a1: false }))
    setAuth(signedIn())
    renderRoute('/shows/show-1')

    expect(await screen.findByText(/everything you picked went while you were away/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hold' })).toBeDisabled()
  })

  it('does not bring back a selection kept for another Show', async () => {
    keepSelection('show-2', { seats: ['a1'], generalAdmission: {} })
    setAuth(signedIn())
    renderRoute('/shows/show-1')

    expect(await screen.findByRole('checkbox', { name: /row A, seat 1, available/ })).not.toBeChecked()
    expect(screen.queryByText(/kept the tickets you picked/i)).not.toBeInTheDocument()
  })

  it('brings the kept selection back only once', async () => {
    await signInToHold()
    await screen.findByRole('checkbox', { name: /row A, seat 1, selected/ })
    expect(keptSelection('show-1')).toBeUndefined()

    cleanup()
    renderRoute('/shows/show-1')
    expect(await screen.findByRole('checkbox', { name: /row A, seat 1, available/ })).not.toBeChecked()
  })

  it('drops the note once the visitor changes the selection', async () => {
    const user = await signInToHold()

    await user.click(await screen.findByRole('checkbox', { name: /row B, seat 1, available/ }))
    expect(screen.queryByText(/kept the tickets you picked/i)).not.toBeInTheDocument()
    expect(summaryLines()).toHaveLength(3)
  })
})

describe('ending a Hold', () => {
  const releasePath = '/api/v1/holds/hold-1/release'
  const releaseRequests = () => api.requests.filter((request) => new URL(request.url).pathname === releasePath)
  const myHoldRequests = () => api.requests.filter((request) => new URL(request.url).pathname === myHoldPath)
  const panel = () => screen.getByRole('region', { name: /your hold/i })
  const summary = () => screen.getByRole('region', { name: /your selection/i })
  // The API's clock, as its Date header tells it.
  const apiDate = (iso: string) => ({ headers: { Date: new Date(iso).toUTCString() } })

  beforeEach(() => {
    setAuth(signedIn())
    vi.useFakeTimers({ shouldAdvanceTime: true })
    // This device's clock is two minutes ahead of the API's.
    vi.setSystemTime(new Date('2099-10-03T13:02:00Z'))
    api.responses[myHoldPath] = () => Response.json(hold(), apiDate('2099-10-03T13:00:00Z'))
  })

  it('counts down to when the Hold expires by the API’s clock, not this device’s', async () => {
    renderRoute('/shows/show-1')

    const timer = await within(await screen.findByRole('region', { name: /your hold/i })).findByRole('timer')
    expect(timer).toHaveTextContent('10:00')
    await act(() => vi.advanceTimersByTimeAsync(65_000))
    expect(timer).toHaveTextContent('8:55')
    expect(within(panel()).queryByText(/under a minute left/i)).not.toBeInTheDocument()
  })

  it('says so in the last minute', async () => {
    renderRoute('/shows/show-1')

    const timer = await within(await screen.findByRole('region', { name: /your hold/i })).findByRole('timer')
    await act(() => vi.advanceTimersByTimeAsync(9 * 60_000 + 1_000))

    expect(timer).toHaveTextContent('0:59')
    expect(within(panel()).getByRole('status')).toHaveTextContent(/under a minute left/i)
  })

  it('shows the Hold as expired at zero, clears its Seats, and asks what is left', async () => {
    renderRoute('/shows/show-1')

    await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, in my Hold' })
    const asked = availabilityRequests().length
    const looked = myHoldRequests().length
    // Reading the Hold past its expiry expires it on the API, which gives its Seats back.
    api.responses[myHoldPath] = () => Response.json({ type: 'urn:getmyseat:problem:not-found', status: 404 }, { status: 404 })
    api.responses[availabilityPath] = () => Response.json(availability({ a1: true }))
    await act(() => vi.advanceTimersByTimeAsync(10 * 60_000))

    expect(within(panel()).getByRole('status')).toHaveTextContent(/expired/i)
    expect(within(panel()).queryByRole('timer')).not.toBeInTheDocument()
    expect(within(panel()).queryByRole('button', { name: 'Pay' })).not.toBeInTheDocument()
    expect(await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, available' })).toBeInTheDocument()
    expect(myHoldRequests().length).toBeGreaterThan(looked)
    expect(availabilityRequests().length).toBeGreaterThan(asked)
  })

  it('keeps showing the Hold as expired if the API still has it for a moment', async () => {
    renderRoute('/shows/show-1')

    await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, in my Hold' })
    await act(() => vi.advanceTimersByTimeAsync(10 * 60_000))

    expect(within(panel()).getByRole('status')).toHaveTextContent(/expired/i)
    expect(screen.queryByRole('checkbox', { name: /in my Hold/ })).not.toBeInTheDocument()
  })

  it('releases the Hold, shows it as released, and asks what is left', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    api.responses[releasePath] = () => {
      api.responses[myHoldPath] = () => Response.json({ type: 'urn:getmyseat:problem:not-found', status: 404 }, { status: 404 })
      api.responses[availabilityPath] = () => Response.json(availability({ a1: true }))
      return Response.json(hold({ status: 'RELEASED' }))
    }
    renderRoute('/shows/show-1')

    await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, in my Hold' })
    const asked = availabilityRequests().length
    await user.click(within(panel()).getByRole('button', { name: 'Release' }))

    expect(await within(panel()).findByRole('status')).toHaveTextContent(/released/i)
    expect(releaseRequests()).toHaveLength(1)
    expect(releaseRequests()[0].method).toBe('POST')
    expect(within(panel()).queryByRole('button', { name: 'Release' })).not.toBeInTheDocument()
    expect(within(panel()).queryByRole('timer')).not.toBeInTheDocument()
    expect(await screen.findByRole('checkbox', { name: 'Balcony, row A, seat 1, available' })).toBeInTheDocument()
    expect(availabilityRequests().length).toBeGreaterThan(asked)
  })

  it('shows a Hold that had already ended as ended, not as an error, when releasing it is a 409', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    api.responses[releasePath] = () => {
      api.responses[myHoldPath] = () => Response.json({ type: 'urn:getmyseat:problem:not-found', status: 404 }, { status: 404 })
      return problemResponse()
    }
    renderRoute('/shows/show-1')

    await user.click(await within(await screen.findByRole('region', { name: /your hold/i })).findByRole('button', { name: 'Release' }))

    expect(await within(panel()).findByRole('status')).toHaveTextContent(/already ended/i)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: /in my Hold/ })).not.toBeInTheDocument()
  })

  it('says it couldn’t release when the 409 leaves the Hold active, and lets the visitor try again', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    api.responses[releasePath] = () => problemResponse()
    renderRoute('/shows/show-1')

    await user.click(await within(await screen.findByRole('region', { name: /your hold/i })).findByRole('button', { name: 'Release' }))

    expect(await within(panel()).findByRole('alert')).toHaveTextContent(/couldn.t release/i)
    expect(within(panel()).getByRole('timer')).toBeInTheDocument()
    expect(within(panel()).getByRole('button', { name: 'Release' })).toBeEnabled()
  })

  it('says a new Hold replaces the current one, and then that it did', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    api.responses[holdsPath] = () =>
      Response.json(
        hold({ id: 'hold-2', items: [{ kind: 'SEAT', sectionId: 's-2', seatId: 'b1', rowLabel: 'B', seatNumber: 1, quantity: 1, pricePaise: 149950 }], totalPaise: 149950 }),
        { status: 201, ...apiDate('2099-10-03T13:00:00Z') },
      )
    renderRoute('/shows/show-1')

    await screen.findByRole('region', { name: /your hold/i })
    await user.click(await screen.findByRole('checkbox', { name: /row B, seat 1, available/ }))
    expect(screen.getByRole('button', { name: 'Hold' })).toHaveAccessibleDescription(/replace your current Hold/i)

    await user.click(screen.getByRole('button', { name: 'Hold' }))

    expect(await within(panel()).findByText(/replaced your earlier one/i)).toBeInTheDocument()
    expect(within(panel()).getByText(/total/i).parentElement).toHaveTextContent('₹1,499.50')
    expect(await screen.findByRole('checkbox', { name: 'Balcony, row B, seat 1, in my Hold' })).toBeInTheDocument()
    expect(screen.queryByText(/replace your current Hold/i)).not.toBeInTheDocument()
    expect(within(summary()).queryByRole('list')).not.toBeInTheDocument()
  })

  it('does not say a Hold will be replaced when there is none', async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    api.responses[myHoldPath] = () => Response.json({ type: 'urn:getmyseat:problem:not-found', status: 404 }, { status: 404 })
    renderRoute('/shows/show-1')

    await user.click(await screen.findByRole('checkbox', { name: /row B, seat 1, available/ }))
    await waitFor(() => expect(myHoldRequests()).toHaveLength(1))
    expect(screen.queryByText(/replace your current Hold/i)).not.toBeInTheDocument()
  })

  it('counts Seats in the visitor’s own Hold as theirs when a kept selection comes back, and on every refresh', async () => {
    keepSelection('show-1', { seats: ['a1', 'b1'], generalAdmission: {} })
    api.responses[availabilityPath] = () => Response.json(availability({ a1: false }))
    // The Hold answers after availability, so the selection has to wait for it.
    api.responses[myHoldPath] = () => new Promise((resolve) => setTimeout(() => resolve(Response.json(hold(), apiDate('2099-10-03T13:00:00Z'))), 50))
    renderRoute('/shows/show-1')

    expect(await within(await screen.findByRole('region', { name: /your selection/i })).findByText(/kept the tickets you picked/i)).toBeInTheDocument()
    expect(screen.queryByText(/went while you were away/i)).not.toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /row A, seat 1, selected/ })).toBeChecked()

    await act(() => vi.advanceTimersByTimeAsync(15_000))
    expect(screen.getByRole('checkbox', { name: /row A, seat 1, selected/ })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: /row B, seat 1, selected/ })).toBeChecked()
  })
})
