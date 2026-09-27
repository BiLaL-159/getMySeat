import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetAuth } from '@/test/fakeAuth.ts'
import { renderRoute } from '@/test/renderRoute.tsx'

vi.mock('react-oidc-context', () => import('@/test/fakeAuth.ts'))
// The landing's imperative layer needs WebGL and layout, which jsdom lacks.
vi.mock('./mountLanding.ts', () => ({ mountLanding: () => () => {} }))

// Answers GET /api/v1/cities from `cities`, and anything else with an empty page of Events.
const api = vi.hoisted(() => ({ cities: (() => new Response()) as () => Response | Promise<Response> }))
vi.mock('@/api/api.ts', async () => {
  const { createApiClient } = await import('@/api/client.ts')
  const { accessToken } = await import('@/test/fakeAuth.ts')
  return {
    api: createApiClient({
      baseUrl: 'http://api.test',
      getAccessToken: accessToken,
      fetch: async (request) => {
        if (new URL(request.url).pathname === '/api/v1/cities') return api.cities()
        return Response.json({ content: [], page: { size: 12, number: 0, totalElements: 0, totalPages: 0 } })
      },
    }),
  }
})

const search = () => screen.getByRole('search')
const citySelect = () => within(search()).getByLabelText(/city/i)

beforeEach(() => {
  resetAuth()
  api.cities = () => Response.json(['Bengaluru', 'Mumbai', 'Pune'])
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
    expect(router.state.location.search).toBe('?from=2026-09-30&to=2026-10-30')
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
    api.cities = () => Response.json({ type: 'about:blank', status: 500 }, { status: 500 })
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
