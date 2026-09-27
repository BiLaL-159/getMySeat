import { describe, expect, it } from 'vitest'
import type { MapSection, SeatState } from './seatMap.ts'
import {
  dropLost,
  emptySelection,
  goneFrom,
  maxTickets,
  pruneSelection,
  setGeneralAdmission,
  summarizeSelection,
  ticketCount,
  toggleSeat,
  type Selection,
} from './selection.ts'

// A Balcony of twelve Seats in row A, all available unless `states` says otherwise, and a Floor.
function sections({ floor = 37, states = {} as Record<string, SeatState> } = {}): MapSection[] {
  return [
    { id: 'floor', name: 'Floor', kind: 'GENERAL_ADMISSION', capacity: 200, available: floor },
    {
      id: 'balcony',
      name: 'Balcony',
      kind: 'SEATED',
      rows: [
        {
          label: 'A',
          seats: Array.from({ length: 12 }, (_, i) => ({
            id: `a${i + 1}`,
            number: i + 1,
            label: `A${i + 1}`,
            state: states[`a${i + 1}`] ?? 'available',
          })),
        },
      ],
    },
  ]
}

const pickSeats = (selection: Selection, ids: string[], map = sections()) =>
  ids.reduce((picked, id) => toggleSeat(picked, id, map), selection)

describe('toggleSeat', () => {
  it('selects an available Seat, and deselects it on a second click', () => {
    const selected = toggleSeat(emptySelection, 'a1', sections())
    expect(selected.seats).toEqual(['a1'])

    expect(toggleSeat(selected, 'a1', sections()).seats).toEqual([])
  })

  it('does not select a Seat held by someone else, or not known yet', () => {
    const map = sections({ states: { a1: 'held', a2: 'unknown' } })

    expect(toggleSeat(emptySelection, 'a1', map)).toBe(emptySelection)
    expect(toggleSeat(emptySelection, 'a2', map)).toBe(emptySelection)
  })

  it(`blocks another Seat at ${maxTickets} tickets, but still lets one go`, () => {
    const full = pickSeats(setGeneralAdmission(emptySelection, 'floor', 2, sections()), ['a1', 'a2', 'a3', 'a4', 'a5', 'a6', 'a7', 'a8'])
    expect(ticketCount(full)).toBe(maxTickets)

    expect(toggleSeat(full, 'a9', sections())).toBe(full)
    expect(toggleSeat(full, 'a1', sections()).seats).not.toContain('a1')
  })
})

describe('setGeneralAdmission', () => {
  it('sets a quantity, and drops the Section at zero', () => {
    const two = setGeneralAdmission(emptySelection, 'floor', 2, sections())
    expect(two.generalAdmission).toEqual({ floor: 2 })

    expect(setGeneralAdmission(two, 'floor', 0, sections()).generalAdmission).toEqual({})
  })

  it('caps the quantity at the places left', () => {
    expect(setGeneralAdmission(emptySelection, 'floor', 5, sections({ floor: 3 })).generalAdmission).toEqual({ floor: 3 })
  })

  it('takes nothing while the places left are not known', () => {
    const unknown = sections().map((section) => (section.kind === 'GENERAL_ADMISSION' ? { ...section, available: undefined } : section))

    expect(setGeneralAdmission(emptySelection, 'floor', 2, unknown).generalAdmission).toEqual({})
  })

  it(`caps the quantity so the selection stays within ${maxTickets} tickets`, () => {
    const sevenSeats = pickSeats(emptySelection, ['a1', 'a2', 'a3', 'a4', 'a5', 'a6', 'a7'])

    expect(setGeneralAdmission(sevenSeats, 'floor', 5, sections()).generalAdmission).toEqual({ floor: 3 })
  })
})

describe('pruneSelection', () => {
  it('drops a selected Seat that is no longer available', () => {
    const picked = pickSeats(emptySelection, ['a1', 'a2'])

    expect(pruneSelection(picked, sections({ states: { a1: 'held' } })).seats).toEqual(['a2'])
  })

  it('lowers a General Admission quantity to the places now left, dropping it at zero', () => {
    const five = setGeneralAdmission(emptySelection, 'floor', 5, sections())

    expect(pruneSelection(five, sections({ floor: 2 })).generalAdmission).toEqual({ floor: 2 })
    expect(pruneSelection(five, sections({ floor: 0 })).generalAdmission).toEqual({})
  })

  it('keeps the same selection when nothing changed', () => {
    const picked = setGeneralAdmission(pickSeats(emptySelection, ['a1']), 'floor', 2, sections())

    expect(pruneSelection(picked, sections())).toBe(picked)
  })
})

describe('summarizeSelection', () => {
  it('lists Seats and General Admission in map order with their Section Price, and the total', () => {
    const picked = setGeneralAdmission(pickSeats(emptySelection, ['a3', 'a1']), 'floor', 2, sections())
    const summary = summarizeSelection(picked, sections(), { floor: 50000, balcony: 149950 })

    expect(summary.lines).toEqual([
      { key: 'floor', section: 'Floor', place: 'General Admission × 2', pricePaise: 50000, amountPaise: 100000 },
      { key: 'a1', section: 'Balcony', place: 'Row A, seat 1', pricePaise: 149950, amountPaise: 149950 },
      { key: 'a3', section: 'Balcony', place: 'Row A, seat 3', pricePaise: 149950, amountPaise: 149950 },
    ])
    expect(summary.totalPaise).toBe(399900)
  })
})

describe('dropLost', () => {
  it('drops only the lost Seats and brings shortfalls down to what is left', () => {
    const selection: Selection = { seats: ['a1', 'a2', 'a3'], generalAdmission: { floor: 4, pit: 2 } }

    expect(dropLost(selection, { unavailableSeats: ['a2'], unavailableSections: [{ sectionId: 'floor', available: 1 }] })).toEqual({
      seats: ['a1', 'a3'],
      generalAdmission: { floor: 1, pit: 2 },
    })
  })

  it('drops a General Admission Section with nothing left', () => {
    const selection: Selection = { seats: [], generalAdmission: { floor: 4 } }

    expect(dropLost(selection, { unavailableSeats: [], unavailableSections: [{ sectionId: 'floor', available: 0 }] })).toEqual(emptySelection)
  })
})

describe('goneFrom', () => {
  it('says which Seats a prune dropped, and what is left where quantities came down', () => {
    const before = { seats: ['a1', 'a2'], generalAdmission: { floor: 3 } }
    const now = sections({ floor: 1, states: { a1: 'held' } })

    expect(goneFrom(before, pruneSelection(before, now), now)).toEqual({
      unavailableSeats: ['a1'],
      unavailableSections: [{ sectionId: 'floor', available: 1 }],
    })
  })

  it('finds nothing gone when the prune changed nothing', () => {
    const before = { seats: ['a1'], generalAdmission: { floor: 3 } }

    expect(goneFrom(before, before, sections())).toEqual({ unavailableSeats: [], unavailableSections: [] })
  })
})
