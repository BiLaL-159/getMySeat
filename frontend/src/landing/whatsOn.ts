import type { EventCard, EventCategory, EventSearchQuery } from '@/api/events.ts'
import { isoDate, plusDays } from './landingSearch.ts'

// An Event with a next Show whose start and Venue time zone are known, as every browse result
// with a date filter has.
export type ScheduledEvent = EventCard & {
  nextShow: NonNullable<EventCard['nextShow']> & { startsAt: string; timeZone: string }
}

export function isScheduled(card: EventCard): card is ScheduledEvent {
  return !!card.nextShow?.startsAt && !!card.nextShow.timeZone
}

// By when each Event's next Show among the ones asked for starts.
const soonestFirst = 'nextShow,asc'

// The gig guide's tabs: every category, or one of them.
export type GuideTab = 'ALL' | EventCategory

// A date as it reads on the wall at a Venue, written as YYYY-MM-DD.
function dateAt(instant: Date, timeZone: string) {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(instant)
}

// The API takes dates at each Venue, and a Venue's today can be the visitor's yesterday or
// tomorrow, so this asks for all three and isOnTonight keeps the ones that are today there.
// Soonest first, tonight's come before tomorrow's, so the first page has all of them.
export function tonightQuery(city: string, today: Date): EventSearchQuery {
  return { city, from: isoDate(plusDays(today, -1)), to: isoDate(plusDays(today, 1)), sort: [soonestFirst], size: 20 }
}

// Whether an Event's next Show is on today at its Venue. The next Show is the soonest one
// still to come, so if it isn't today, none is.
export function isOnTonight({ nextShow }: ScheduledEvent, now: Date) {
  return dateAt(new Date(nextShow.startsAt), nextShow.timeZone) === dateAt(now, nextShow.timeZone)
}

// Events with a Show still to come. The API only gives Shows yet to start, so asking from the
// visitor's yesterday keeps a Show later today at a Venue whose date is behind the visitor's.
export function guideQuery(tab: GuideTab, today: Date): EventSearchQuery {
  return { ...(tab === 'ALL' ? {} : { category: tab }), from: isoDate(plusDays(today, -1)), sort: [soonestFirst], size: 12 }
}

// A remembered city counts only while it's still one of the cities; otherwise it's any city.
export function knownCity(remembered: string | null, cities: string[] | undefined) {
  return remembered && cities?.includes(remembered) ? remembered : ''
}

// Tonight is in the chosen city, or with none chosen, the first one.
export function tonightCity(chosen: string, cities: string[] | undefined) {
  return chosen || cities?.[0]
}
