import { describe, expect, it } from 'vitest'
import type { SectionDetail, ShowAvailability } from '@/api/shows.ts'
import { buildSeatMap } from './seatMap.ts'

const seat = (id: string, row: string, number: number) => ({ id, row, number, label: `${row}${number}` })

const balcony: SectionDetail = {
  id: 's-2',
  name: 'Balcony',
  kind: 'SEATED',
  seats: [seat('b2', 'B', 2), seat('a10', 'A', 10), seat('b1', 'B', 1), seat('a2', 'A', 2), seat('a1', 'A', 1)],
}
const floor: SectionDetail = { id: 's-1', name: 'Floor', kind: 'GENERAL_ADMISSION', capacity: 200 }

describe('buildSeatMap', () => {
  it('keeps Sections in layout order, with Seats by row, sorted by number', () => {
    const map = buildSeatMap([floor, balcony], undefined)

    expect(map.map((section) => section.name)).toEqual(['Floor', 'Balcony'])
    const seated = map[1]
    expect(seated.kind).toBe('SEATED')
    if (seated.kind !== 'SEATED') return
    expect(seated.rows.map((row) => [row.label, row.seats.map((s) => s.number)])).toEqual([
      ['B', [1, 2]],
      ['A', [1, 2, 10]],
    ])
  })

  it('marks each Seat available or held from the availability', () => {
    const availability: ShowAvailability = {
      sections: [
        { id: 's-1', kind: 'GENERAL_ADMISSION', capacity: 200, available: 37 },
        {
          id: 's-2',
          kind: 'SEATED',
          seats: [
            { id: 'a1', available: true },
            { id: 'a2', available: false },
          ],
        },
      ],
    }
    const [generalAdmission, seated] = buildSeatMap([floor, balcony], availability)

    expect(generalAdmission).toMatchObject({ kind: 'GENERAL_ADMISSION', capacity: 200, available: 37 })
    if (seated.kind !== 'SEATED') throw new Error('expected a Seated Section')
    const states = Object.fromEntries(seated.rows.flatMap((row) => row.seats.map((s) => [s.id, s.state])))
    // A Seat the availability doesn't mention is unknown, not assumed free.
    expect(states).toEqual({ a1: 'available', a2: 'held', a10: 'unknown', b1: 'unknown', b2: 'unknown' })
  })

  it('leaves every Seat and place unknown while there is no availability', () => {
    const [generalAdmission, seated] = buildSeatMap([floor, balcony], undefined)

    expect(generalAdmission).toMatchObject({ kind: 'GENERAL_ADMISSION', capacity: 200, available: undefined })
    if (seated.kind !== 'SEATED') throw new Error('expected a Seated Section')
    expect(seated.rows.flatMap((row) => row.seats).every((s) => s.state === 'unknown')).toBe(true)
  })

  it('draws a Seated Section with no Seats as having no rows', () => {
    const [empty] = buildSeatMap([{ id: 's-3', name: 'Box', kind: 'SEATED' }], undefined)

    expect(empty).toMatchObject({ kind: 'SEATED', rows: [] })
  })
})
