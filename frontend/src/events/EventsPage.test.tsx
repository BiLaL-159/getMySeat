import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetSession } from '@/auth/session.ts'
import { resetAuth, signinRedirect } from '@/test/fakeAuth.ts'
import { renderRoute } from '@/test/renderRoute.tsx'

vi.mock('react-oidc-context', () => import('@/test/fakeAuth.ts'))

// Answers GET /api/v1/events from `search`, recording each request's query.
const api = vi.hoisted(() => ({ search: (() => new Response()) as (url: URL) => Response | Promise<Response>, queries: [] as URLSearchParams[] }))
vi.mock('@/api/api.ts', async () => {
  const { createApiClient } = await import('@/api/client.ts')
  const { accessToken } = await import('@/test/fakeAuth.ts')
  return {
    api: createApiClient({
      baseUrl: 'http://api.test',
      getAccessToken: accessToken,
      fetch: async (request) => {
        const url = new URL(request.url)
        if (url.pathname !== '/api/v1/events') {
          return Response.json({ type: 'urn:getmyseat:problem:not-found', status: 404 }, { status: 404 })
        }
        api.queries.push(url.searchParams)
        return api.search(url)
      },
    }),
  }
})

const indieNight = {
  id: 'event-1',
  title: 'Indie Night',
  category: 'MUSIC',
  description: 'Three bands, one long night.',
  status: 'PUBLISHED',
  nextShow: { id: 'show-1', startsAt: '2026-10-03T14:00:00Z', venueName: 'Harbourline Arena', city: 'Mumbai', timeZone: 'Asia/Kolkata' },
  lowestPrice: { amountPaise: 45000, currency: 'INR' },
}
const openMic = { id: 'event-2', title: 'Open Mic', category: 'COMEDY', description: 'Five minutes each.', status: 'PUBLISHED' }

function page(content: unknown[], { number = 0, totalPages = 1 } = {}) {
  return Response.json({ content, page: { size: 12, number, totalElements: totalPages * 12, totalPages } })
}

const lastQuery = () => Object.fromEntries(api.queries.at(-1)!)

beforeEach(() => {
  resetAuth()
  api.queries = []
  api.search = () => page([indieNight, openMic])
})

afterEach(resetSession)

describe('events page', () => {
  it('opens for a signed-out visitor, newest first', async () => {
    renderRoute('/events')

    expect(await screen.findByRole('link', { name: /indie night/i })).toBeInTheDocument()
    expect(signinRedirect).not.toHaveBeenCalled()
    expect(lastQuery()).toEqual({ sort: 'publishedAt,desc', page: '0', size: '12' })
  })

  it('shows each result as a card linking to its Event', async () => {
    renderRoute('/events')

    const results = await screen.findByRole('list', { name: /events/i })
    const cards = within(results).getAllByRole('listitem')
    expect(cards.map((card) => card.textContent)).toEqual([
      expect.stringMatching(/Indie Night.*Music.*Three bands, one long night\./),
      expect.stringMatching(/Open Mic.*Comedy.*Five minutes each\./),
    ])
    expect(within(cards[0]).getByRole('link', { name: /indie night/i })).toHaveAttribute('href', '/events/event-1')
  })

  it('shows when and where an Event is next on, and its lowest price', async () => {
    renderRoute('/events')

    const card = (await screen.findByRole('link', { name: /indie night/i })).closest('li')!
    expect(card).toHaveTextContent('Sat, 3 Oct, 2026, 7:30 pm IST')
    expect(card).toHaveTextContent('Harbourline Arena, Mumbai')
    expect(card).toHaveTextContent('from ₹450')
    expect(within(card).getByText(/7:30 pm/).closest('time')).toHaveAttribute('dateTime', '2026-10-03T14:00:00Z')
  })

  it('says when an Event has no upcoming Show, with no price', async () => {
    renderRoute('/events')

    const card = (await screen.findByRole('link', { name: /open mic/i })).closest('li')!
    expect(card).toHaveTextContent('No upcoming Shows')
    expect(card).not.toHaveTextContent(/from ₹/)
  })

  it('pictures each Event with artwork for its category', async () => {
    renderRoute('/events')

    const indie = (await screen.findByRole('link', { name: /indie night/i })).closest('li')!
    const mic = screen.getByRole('link', { name: /open mic/i }).closest('li')!
    expect(within(indie).getByRole('presentation')).toHaveAttribute('src', '/assets-v9/p-monsoon.webp')
    expect(within(mic).getByRole('presentation')).toHaveAttribute('src', '/assets-v9/p-openmic.webp')
  })

  it('searches with the filters in the URL, and fills the form with them', async () => {
    renderRoute('/events?q=indie&city=Mumbai&category=MUSIC&from=2026-10-01&to=2026-10-31&sort=title&page=2')

    await screen.findByRole('link', { name: /indie night/i })
    expect(lastQuery()).toEqual({
      q: 'indie',
      city: 'Mumbai',
      category: 'MUSIC',
      from: '2026-10-01',
      to: '2026-10-31',
      sort: 'title,asc',
      page: '1',
      size: '12',
    })
    expect(screen.getByRole('searchbox', { name: /search/i })).toHaveValue('indie')
    expect(screen.getByRole('textbox', { name: /city/i })).toHaveValue('Mumbai')
    expect(screen.getByRole('combobox', { name: /category/i })).toHaveValue('MUSIC')
    expect(screen.getByLabelText(/^from/i)).toHaveValue('2026-10-01')
    expect(screen.getByLabelText(/^to/i)).toHaveValue('2026-10-31')
    expect(screen.getByRole('combobox', { name: /sort/i })).toHaveValue('title')
  })

  it('ignores filter values it does not understand', async () => {
    const router = renderRoute('/events?category=JAZZ&from=soon&sort=price&page=-1&q=indie')

    await screen.findByRole('link', { name: /indie night/i })
    expect(lastQuery()).toEqual({ q: 'indie', sort: 'publishedAt,desc', page: '0', size: '12' })
    expect(screen.getByRole('combobox', { name: /category/i })).toHaveValue('')
    expect(router.state.location.search).toBe('?category=JAZZ&from=soon&sort=price&page=-1&q=indie')
  })

  it('puts a new search in the URL, back on the first page', async () => {
    const user = userEvent.setup()
    const router = renderRoute('/events?page=3')
    await screen.findByRole('link', { name: /indie night/i })

    await user.type(screen.getByRole('searchbox', { name: /search/i }), 'stand up')
    await user.type(screen.getByRole('textbox', { name: /city/i }), 'Pune')
    await user.selectOptions(screen.getByRole('combobox', { name: /category/i }), 'Comedy')
    await user.click(screen.getByRole('button', { name: /^search$/i }))

    expect(router.state.location.search).toBe('?q=stand+up&city=Pune&category=COMEDY')
    await vi.waitFor(() => expect(lastQuery()).toMatchObject({ q: 'stand up', city: 'Pune', category: 'COMEDY', page: '0' }))
  })

  it('sorts by title', async () => {
    const user = userEvent.setup()
    const router = renderRoute('/events?q=indie')
    await screen.findByRole('link', { name: /indie night/i })

    await user.selectOptions(screen.getByRole('combobox', { name: /sort/i }), 'Title, A to Z')

    expect(router.state.location.search).toBe('?q=indie&sort=title')
    await vi.waitFor(() => expect(lastQuery()).toMatchObject({ q: 'indie', sort: 'title,asc' }))
  })

  it('sorts soonest first', async () => {
    const user = userEvent.setup()
    const router = renderRoute('/events?q=indie')
    await screen.findByRole('link', { name: /indie night/i })

    await user.selectOptions(screen.getByRole('combobox', { name: /sort/i }), 'Soonest first')

    expect(router.state.location.search).toBe('?q=indie&sort=nextShow')
    await vi.waitFor(() => expect(lastQuery()).toMatchObject({ q: 'indie', sort: 'nextShow,asc' }))
  })

  it('pages through the results, keeping the filters', async () => {
    const user = userEvent.setup()
    api.search = (url) => page([indieNight], { number: Number(url.searchParams.get('page')), totalPages: 3 })
    const router = renderRoute('/events?q=indie')

    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /previous/i })).not.toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: /next/i }))

    expect(router.state.location.search).toBe('?q=indie&page=2')
    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /previous/i })).toHaveAttribute('href', '/events?q=indie')
    expect(screen.getByRole('link', { name: /next/i })).toHaveAttribute('href', '/events?q=indie&page=3')
  })

  it('keeps the page it shows labelled while the next one loads', async () => {
    const user = userEvent.setup()
    api.search = () => page([indieNight], { totalPages: 3 })
    renderRoute('/events')
    await screen.findByText('Page 1 of 3')

    let respond!: (response: Response) => void
    api.search = () => new Promise((resolve) => (respond = resolve))
    await user.click(screen.getByRole('link', { name: /next/i }))

    expect(screen.getByRole('list', { name: /events/i })).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByText('Page 1 of 3')).toBeInTheDocument()
    respond(page([openMic], { number: 1, totalPages: 3 }))
    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /open mic/i })).toBeInTheDocument()
  })

  it('does not offer pages when there is only one', async () => {
    renderRoute('/events')

    await screen.findByRole('link', { name: /indie night/i })
    expect(screen.queryByText(/page 1 of/i)).not.toBeInTheDocument()
  })

  it('says when nothing matches, and clears the filters', async () => {
    const user = userEvent.setup()
    api.search = (url) => (url.searchParams.has('q') ? page([], { totalPages: 0 }) : page([indieNight]))
    const router = renderRoute('/events?q=nothing&sort=title')

    expect(await screen.findByRole('heading', { name: /no events match/i })).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: /clear the filters/i }))

    expect(router.state.location.search).toBe('')
    expect(await screen.findByRole('link', { name: /indie night/i })).toBeInTheDocument()
    expect(screen.getByRole('searchbox', { name: /search/i })).toHaveValue('')
  })

  it('offers the first page when a page is past the end', async () => {
    api.search = (url) => (url.searchParams.get('page') === '0' ? page([indieNight], { totalPages: 2 }) : page([], { number: 8, totalPages: 2 }))
    renderRoute('/events?q=indie&page=9')

    expect(await screen.findByText(/no events on this page/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /first page/i })).toHaveAttribute('href', '/events?q=indie')
  })

  it('says the API cannot be reached, and retries', async () => {
    const user = userEvent.setup()
    api.search = () => {
      throw new TypeError('Failed to fetch')
    }
    renderRoute('/events')

    expect(await screen.findByRole('alert')).toHaveTextContent(/can.t reach getmyseat/i)
    api.search = () => page([indieNight])
    await user.click(screen.getByRole('button', { name: /try again/i }))
    expect(await screen.findByRole('link', { name: /indie night/i })).toBeInTheDocument()
  })
})
