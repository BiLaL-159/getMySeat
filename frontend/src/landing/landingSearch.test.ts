import { describe, expect, it } from 'vitest'
import { landingSearchPath, whenRange } from './landingSearch.ts'

// Local dates, the way the visitor's calendar reads them.
const wednesday = new Date(2026, 8, 30, 21, 15)
const saturday = new Date(2026, 9, 3, 10, 0)
const sunday = new Date(2026, 9, 4, 23, 59)

describe('whenRange', () => {
  it('makes "This weekend" the coming Saturday and Sunday', () => {
    expect(whenRange('weekend', wednesday)).toEqual({ from: '2026-10-03', to: '2026-10-04' })
  })

  it('keeps "This weekend" to what is left of it on a Saturday or Sunday', () => {
    expect(whenRange('weekend', saturday)).toEqual({ from: '2026-10-03', to: '2026-10-04' })
    expect(whenRange('weekend', sunday)).toEqual({ from: '2026-10-04', to: '2026-10-04' })
  })

  it('makes "Next 30 days" run from today, across months', () => {
    expect(whenRange('30days', wednesday)).toEqual({ from: '2026-09-30', to: '2026-10-30' })
  })

  it('sends no dates for "Any time"', () => {
    expect(whenRange('any', wednesday)).toEqual({})
  })
})

describe('landingSearchPath', () => {
  it('goes to /events with the search and its dates', () => {
    expect(landingSearchPath({ q: 'jazz', city: 'Pune', when: 'weekend' }, wednesday)).toBe(
      '/events?q=jazz&city=Pune&from=2026-10-03&to=2026-10-04',
    )
  })

  it('leaves out empty values', () => {
    expect(landingSearchPath({ q: '  ', city: '', when: 'any' }, wednesday)).toBe('/events')
    expect(landingSearchPath({ q: ' Open mic ', city: '', when: 'any' }, wednesday)).toBe('/events?q=Open+mic')
  })
})
