import { describe, expect, it } from 'vitest'
import { guideQuery, isOnTonight, isScheduled, knownCity, type ScheduledEvent, soonestFirst, tonightCity, tonightQuery } from './whatsOn.ts'

const card = (startsAt: string, timeZone = 'Asia/Kolkata'): ScheduledEvent => ({
  id: startsAt,
  nextShow: { id: 's', startsAt, timeZone, venueName: 'Kiln Yard', city: 'Mumbai' },
})

describe('tonight', () => {
  it('asks for the city from the day before to the day after, so every Venue time zone is covered', () => {
    expect(tonightQuery('Pune', new Date(2026, 8, 30, 12, 0))).toEqual({
      city: 'Pune',
      from: '2026-09-29',
      to: '2026-10-01',
      size: 20,
    })
  })

  it("keeps an Event whose next Show is on today's date at its Venue", () => {
    // 9 pm in Mumbai on 30 September is 3:30 pm UTC.
    expect(isOnTonight(card('2026-09-30T15:30:00Z'), new Date('2026-09-30T06:30:00Z'))).toBe(true)
  })

  it('drops an Event whose next Show is tomorrow at its Venue', () => {
    expect(isOnTonight(card('2026-10-01T15:30:00Z'), new Date('2026-09-30T06:30:00Z'))).toBe(false)
  })

  it("goes by the Venue's date, not the visitor's", () => {
    // 20:00 UTC on 30 September is already 1 October in Mumbai, but still 30 September in New York.
    const now = new Date('2026-09-30T20:00:00Z')
    expect(isOnTonight(card('2026-10-01T14:00:00Z'), now)).toBe(true)
    expect(isOnTonight(card('2026-10-01T14:00:00Z', 'America/New_York'), now)).toBe(false)
  })

  it('knows when an Event is next on only if it has a next Show with its start and time zone', () => {
    expect(isScheduled(card('2026-09-30T15:30:00Z'))).toBe(true)
    expect(isScheduled({ id: 'e' })).toBe(false)
    expect(isScheduled({ id: 'e', nextShow: { startsAt: '2026-09-30T15:30:00Z' } })).toBe(false)
  })
})

describe('gig guide', () => {
  it('asks for Events with a Show from today on, of the category unless it is all of them', () => {
    const today = new Date(2026, 8, 30, 12, 0)
    expect(guideQuery('ALL', today)).toEqual({ from: '2026-09-30', size: 12 })
    expect(guideQuery('COMEDY', today)).toEqual({ category: 'COMEDY', from: '2026-09-30', size: 12 })
  })

  it('puts the soonest next Show first', () => {
    const later = card('2026-10-09T15:30:00Z')
    const sooner = card('2026-10-02T15:30:00Z')
    expect(soonestFirst([later, sooner])).toEqual([sooner, later])
  })
})

describe('city', () => {
  it('keeps a remembered city only while it is still one of the cities', () => {
    expect(knownCity('Pune', ['Mumbai', 'Pune'])).toBe('Pune')
    expect(knownCity('Goa', ['Mumbai', 'Pune'])).toBe('')
    expect(knownCity('Pune', undefined)).toBe('')
    expect(knownCity(null, ['Mumbai'])).toBe('')
  })

  it('is tonight in the chosen city, or else the first city', () => {
    expect(tonightCity('Pune', ['Mumbai', 'Pune'])).toBe('Pune')
    expect(tonightCity('', ['Mumbai', 'Pune'])).toBe('Mumbai')
    expect(tonightCity('', [])).toBeUndefined()
    expect(tonightCity('', undefined)).toBeUndefined()
  })
})
