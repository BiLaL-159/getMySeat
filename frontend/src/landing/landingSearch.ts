import { defaultSearch, writeSearch } from '@/events/search.ts'

export type When = 'weekend' | '30days' | 'any'

export const whenLabels: Record<When, string> = {
  weekend: 'This weekend',
  '30days': 'Next 30 days',
  any: 'Any time',
}

// What the landing page's search form holds. An empty city means any city.
export type LandingSearch = { q: string; city: string; when: When }

// A local date written as YYYY-MM-DD, the only form the API takes.
export function isoDate(date: Date) {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function plusDays(date: Date, days: number) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days)
}

// The dates a "When" choice covers, from the visitor's today. The API takes them in each Venue's
// time zone, which in practice is the visitor's.
export function whenRange(when: When, today: Date): { from?: string; to?: string } {
  switch (when) {
    case 'weekend': {
      // Sunday is 0: on a Sunday the weekend is just today.
      const day = today.getDay()
      const saturday = day === 0 ? plusDays(today, -1) : plusDays(today, 6 - day)
      const from = day === 0 || day === 6 ? today : saturday
      return { from: isoDate(from), to: isoDate(plusDays(saturday, 1)) }
    }
    case '30days':
      // Both ends count, so today and the 29 days after it.
      return { from: isoDate(today), to: isoDate(plusDays(today, 29)) }
    case 'any':
      return {}
  }
}

// Where the landing page's search leads: the /events results, leaving out empty values.
export function landingSearchPath({ q, city, when }: LandingSearch, today: Date) {
  const params = writeSearch({ ...defaultSearch, q: q.trim(), city, ...whenRange(when, today) })
  const query = params.toString()
  return query ? `/events?${query}` : '/events'
}
