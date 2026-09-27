import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetAuth } from '@/test/fakeAuth.ts'
import { renderRoute } from '@/test/renderRoute.tsx'
import type { EventCard } from '@/api/events.ts'

vi.mock('react-oidc-context', () => import('@/test/fakeAuth.ts'))
// The landing's imperative layer needs WebGL and layout, which jsdom lacks.
vi.mock('./mountLanding.ts', () => ({ mountLanding: () => () => {} }))

// Answers GET /api/v1/cities from `cities` and GET /api/v1/events from `events`.
const api = vi.hoisted(() => ({
  cities: (() => new Response()) as () => Response | Promise<Response>,
  events: (() => new Response()) as (query: URLSearchParams) => Response | Promise<Response>,
}))
vi.mock('@/api/api.ts', async () => {
  const { createApiClient } = await import('@/api/client.ts')
  const { accessToken } = await import('@/test/fakeAuth.ts')
  return {
    api: createApiClient({
      baseUrl: 'http://api.test',
      getAccessToken: accessToken,
      fetch: async (request) => {
        const url = new URL(request.url)
        if (url.pathname === '/api/v1/cities') return api.cities()
        if (url.pathname === '/api/v1/events') return api.events(url.searchParams)
        return new Response(null, { status: 404 })
      },
    }),
  }
})

const page = (content: EventCard[]) =>
  Response.json({ content, page: { size: 12, number: 0, totalElements: content.length, totalPages: content.length ? 1 : 0 } })
const unreachable = () => Response.json({ type: 'about:blank', status: 500 }, { status: 500 })

// An Event on at Kiln Yard, whose next Show starts at `startsAt`.
const event = (id: string, title: string, startsAt: string, more: Partial<EventCard> = {}): EventCard => ({
  id,
  title,
  category: 'MUSIC',
  nextShow: { id: `${id}-show`, startsAt, venueName: 'Kiln Yard', city: 'Mumbai', timeZone: 'Asia/Kolkata' },
  lowestPrice: { amountPaise: 99900, currency: 'INR' },
  ...more,
})
// On 30 September, the day the tests run, 9 pm in Mumbai; and the same time the next day.
const tonight = '2026-09-30T15:30:00Z'
const tomorrow = '2026-10-01T15:30:00Z'

const search = () => screen.getByRole('search')
const citySelect = () => within(search()).getByLabelText(/city/i)

beforeEach(() => {
  resetAuth()
  api.cities = () => Response.json(['Bengaluru', 'Mumbai', 'Pune'])
  api.events = () => page([])
  localStorage.clear()
  // A Wednesday, so "This weekend" is the coming Saturday and Sunday.
  vi.useFakeTimers({ now: new Date(2026, 8, 30, 12, 0), toFake: ['Date'] })
})

afterEach(() => {
  vi.useRealTimers()
})

describe('landing search', () => {
  it('offers the cities from the API, after any city', async () => {
    renderRoute('/')

    expect(await within(await screen.findByRole('search')).findByRole('option', { name: 'Pune' })).toBeInTheDocument()
    expect(within(citySelect()).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Any city',
      'Bengaluru',
      'Mumbai',
      'Pune',
    ])
  })

  it('goes to /events with the search, the city and the weekend as dates', async () => {
    const user = userEvent.setup()
    const router = renderRoute('/')

    await user.selectOptions(await screen.findByLabelText(/city/i), await screen.findByRole('option', { name: 'Pune' }))
    await user.type(within(search()).getByLabelText(/who or what/i), 'jazz')
    await user.selectOptions(within(search()).getByLabelText(/when/i), 'This weekend')
    await user.click(within(search()).getByRole('button', { name: /find seats/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/events'))
    expect(Object.fromEntries(new URLSearchParams(router.state.location.search))).toEqual({
      q: 'jazz',
      city: 'Pune',
      from: '2026-10-03',
      to: '2026-10-04',
    })
  })

  it('turns "Next 30 days" into dates from today', async () => {
    const user = userEvent.setup()
    const router = renderRoute('/')

    await user.selectOptions(within(await screen.findByRole('search')).getByLabelText(/when/i), 'Next 30 days')
    await user.click(within(search()).getByRole('button', { name: /find seats/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/events'))
    expect(router.state.location.search).toBe('?from=2026-09-30&to=2026-10-29')
  })

  it('leaves out everything left empty, with "Any time" sending no dates', async () => {
    const user = userEvent.setup()
    const router = renderRoute('/')

    await user.selectOptions(within(await screen.findByRole('search')).getByLabelText(/when/i), 'Any time')
    await user.click(within(search()).getByRole('button', { name: /find seats/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/events'))
    expect(router.state.location.search).toBe('')
  })

  it('still searches, with no city filter, when the cities cannot be loaded', async () => {
    const user = userEvent.setup()
    api.cities = unreachable
    const router = renderRoute('/')

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(within(citySelect()).getAllByRole('option').map((o) => o.textContent)).toEqual(['Any city'])
    await user.type(within(search()).getByLabelText(/who or what/i), 'comedy')
    await user.selectOptions(within(search()).getByLabelText(/when/i), 'Any time')
    await user.click(within(search()).getByRole('button', { name: /find seats/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/events'))
    expect(router.state.location.search).toBe('?q=comedy')
  })
})

describe('tonight strip', () => {
  const strip = () => screen.getByRole('list', { name: /tonight in/i })

  it("lists the first city's Events with a Show today at their Venue, each linking to its Event", async () => {
    api.events = (query) =>
      query.get('city') === 'Bengaluru' && query.get('sort') === 'nextShow,asc'
        ? page([event('jazz', 'Kiln Yard Jazz Sessions', tonight), event('tomorrow', 'Tughlaq', tomorrow)])
        : page([])
    renderRoute('/')

    expect(await screen.findByRole('link', { name: /kiln yard jazz sessions/i })).toHaveAttribute('href', '/events/jazz')
    expect(screen.getByText('Tonight in Bengaluru')).toBeInTheDocument()
    expect(strip()).toHaveTextContent('9:00 pm · Kiln Yard')
    expect(within(strip()).queryByText('Tughlaq')).not.toBeInTheDocument()
  })

  it('follows the city chosen in the search form, and remembers it for the next visit', async () => {
    const user = userEvent.setup()
    api.events = (query) => (query.get('city') === 'Pune' ? page([event('kabaddi', 'Kabaddi: Pune vs Jaipur', tonight)]) : page([]))
    renderRoute('/')

    await user.selectOptions(await screen.findByLabelText(/city/i), await screen.findByRole('option', { name: 'Pune' }))
    expect(await screen.findByText('Tonight in Pune')).toBeInTheDocument()
    expect(await within(strip()).findByRole('link', { name: /kabaddi/i })).toBeInTheDocument()

    cleanup()
    renderRoute('/')
    expect(await screen.findByText('Tonight in Pune')).toBeInTheDocument()
    expect(await within(search()).findByRole('option', { name: 'Pune', selected: true })).toBeInTheDocument()
  })

  it('falls back to the first city when the remembered one is no longer listed', async () => {
    localStorage.setItem('getmyseat.landing.city', 'Goa')
    renderRoute('/')

    expect(await screen.findByText('Tonight in Bengaluru')).toBeInTheDocument()
    expect(within(search()).getByRole('option', { name: 'Any city', selected: true })).toBeInTheDocument()
  })

  it('says when nothing is on tonight', async () => {
    renderRoute('/')

    expect(await within(await screen.findByRole('list', { name: 'Tonight in Bengaluru' })).findByText(/nothing on tonight/i)).toBeInTheDocument()
  })
})

describe('gig guide', () => {
  const guide = () => screen.getByRole('region', { name: /what's on/i })
  const findGuide = () => screen.findByRole('region', { name: /what's on/i })

  it('lists upcoming Events soonest first, with where, when and from what price, each linking to its Event', async () => {
    api.events = (query) =>
      query.get('city') || query.get('sort') !== 'nextShow,asc'
        ? page([])
        : page([
            event('sooner', 'Kiln Yard Jazz Sessions', '2026-10-02T15:30:00Z'),
            event('later', 'Tughlaq', '2026-10-24T13:30:00Z', { category: 'THEATRE', lowestPrice: { amountPaise: 120000, currency: 'INR' } }),
          ])
    renderRoute('/')

    const links = await within(await findGuide()).findAllByRole('link')
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/events/sooner', '/events/later'])
    expect(links[0]).toHaveTextContent('Kiln Yard Jazz Sessions')
    expect(links[0]).toHaveTextContent('Kiln Yard, Mumbai · Music · 9:00 pm')
    expect(links[0]).toHaveTextContent('2Oct')
    expect(links[0]).toHaveTextContent('From₹999')
    expect(links[1]).toHaveTextContent('From₹1,200')
  })

  it('filters by category', async () => {
    const user = userEvent.setup()
    api.events = (query) =>
      query.get('city')
        ? page([])
        : query.get('category') === 'COMEDY'
          ? page([event('mic', 'Open Mic at Tin Roof', tomorrow, { category: 'COMEDY' })])
          : page([event('jazz', 'Kiln Yard Jazz Sessions', tomorrow)])
    renderRoute('/')

    expect(await within(await findGuide()).findByRole('link', { name: /kiln yard jazz/i })).toBeInTheDocument()
    await user.click(within(guide()).getByRole('button', { name: 'Comedy' }))

    expect(await within(guide()).findByRole('link', { name: /open mic/i })).toBeInTheDocument()
    expect(within(guide()).queryByRole('link', { name: /kiln yard jazz/i })).not.toBeInTheDocument()
    expect(within(guide()).getByRole('button', { name: 'Comedy' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('says when nothing is coming up', async () => {
    renderRoute('/')

    expect(await within(await findGuide()).findByText(/nothing coming up/i)).toBeInTheDocument()
  })
})

describe('when the API is unreachable', () => {
  it('still renders the landing page, saying what could not be loaded', async () => {
    api.cities = unreachable
    api.events = unreachable
    renderRoute('/')

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(await within(screen.getByRole('region', { name: /what's on/i })).findByText(/couldn.t load/i)).toBeInTheDocument()
    expect(await screen.findByText(/couldn.t load tonight/i)).toBeInTheDocument()
  })
})
