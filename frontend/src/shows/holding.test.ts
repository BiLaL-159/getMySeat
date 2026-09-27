import { describe, expect, it } from 'vitest'
import { ApiError, type Problem } from '@/api/problem.ts'
import { createAttemptKeys, describeLoss, holdRequest, shouldRetryHold } from './holding.ts'
import type { MapSection } from './seatMap.ts'

const sections: MapSection[] = [
  { id: 'floor', name: 'Floor', kind: 'GENERAL_ADMISSION', capacity: 200, available: 37 },
  {
    id: 'balcony',
    name: 'Balcony',
    kind: 'SEATED',
    rows: [
      {
        label: 'A',
        seats: [
          { id: 'a1', number: 1, label: 'A1', state: 'available' },
          { id: 'a2', number: 2, label: 'A2', state: 'available' },
        ],
      },
    ],
  },
]

describe('holdRequest', () => {
  it('asks for the picked Seats and General Admission quantities, in a steady order', () => {
    expect(holdRequest({ seats: ['a2', 'a1'], generalAdmission: { pit: 1, floor: 3 } })).toEqual({
      seats: ['a1', 'a2'],
      generalAdmission: [
        { sectionId: 'floor', quantity: 3 },
        { sectionId: 'pit', quantity: 1 },
      ],
    })
  })
})

describe('createAttemptKeys', () => {
  const counting = () => {
    let n = 0
    return createAttemptKeys(() => `key-${++n}`)
  }

  it('reuses the key when the same tickets are asked for again', () => {
    const keys = counting()
    const first = keys.keyFor(holdRequest({ seats: ['a1', 'a2'], generalAdmission: { floor: 2 } }))
    expect(keys.keyFor(holdRequest({ seats: ['a2', 'a1'], generalAdmission: { floor: 2 } }))).toBe(first)
  })

  it('gives a changed selection a new key', () => {
    const keys = counting()
    const first = keys.keyFor(holdRequest({ seats: ['a1'], generalAdmission: {} }))
    const second = keys.keyFor(holdRequest({ seats: ['a1'], generalAdmission: { floor: 1 } }))
    expect(second).not.toBe(first)
    // Going back to the first selection is a new attempt too.
    expect(keys.keyFor(holdRequest({ seats: ['a1'], generalAdmission: {} }))).not.toBe(first)
  })

  it('starts afresh once an attempt is done with', () => {
    const keys = counting()
    const request = holdRequest({ seats: ['a1'], generalAdmission: {} })
    const first = keys.keyFor(request)
    keys.forget()
    expect(keys.keyFor(request)).not.toBe(first)
  })
})

describe('shouldRetryHold', () => {
  const error = (problem: Problem) => new ApiError(problem)

  it.each([
    ['a plain conflict', { kind: 'conflict', status: 409 }],
    ['a network failure', { kind: 'unreachable' }],
    ['a server failure', { kind: 'unknown', status: 500 }],
    ['a race that names nothing lost', { kind: 'inventory-unavailable', status: 409, unavailableSeats: [], unavailableSections: [] }],
  ] satisfies [string, Problem][])('retries %s', (_, problem) => {
    expect(shouldRetryHold(0, error(problem))).toBe(true)
  })

  it.each([
    ['lost Seats', { kind: 'inventory-unavailable', status: 409, unavailableSeats: ['a1'], unavailableSections: [] }],
    ['a General Admission shortfall', { kind: 'inventory-unavailable', status: 409, unavailableSeats: [], unavailableSections: [{ sectionId: 'floor', available: 1 }] }],
    ['a bad request', { kind: 'validation', status: 400, fieldErrors: {} }],
    ['a missing Show', { kind: 'not-found', status: 404 }],
  ] satisfies [string, Problem][])('does not retry %s', (_, problem) => {
    expect(shouldRetryHold(0, error(problem))).toBe(false)
  })

  it('gives up after three retries', () => {
    expect(shouldRetryHold(2, error({ kind: 'conflict', status: 409 }))).toBe(true)
    expect(shouldRetryHold(3, error({ kind: 'conflict', status: 409 }))).toBe(false)
  })
})

describe('describeLoss', () => {
  it('names each lost Seat and each General Admission shortfall, in map order', () => {
    expect(
      describeLoss({ unavailableSeats: ['a2'], unavailableSections: [{ sectionId: 'floor', available: 1 }] }, sections),
    ).toEqual(['Floor: only 1 place left', 'Balcony, row A, seat 2'])
  })

  it('says a General Admission Section with nothing left is sold out', () => {
    expect(describeLoss({ unavailableSeats: [], unavailableSections: [{ sectionId: 'floor', available: 0 }] }, sections)).toEqual([
      'Floor: sold out',
    ])
  })

  it('counts places in the plural', () => {
    expect(describeLoss({ unavailableSeats: [], unavailableSections: [{ sectionId: 'floor', available: 3 }] }, sections)).toEqual([
      'Floor: only 3 places left',
    ])
  })
})
