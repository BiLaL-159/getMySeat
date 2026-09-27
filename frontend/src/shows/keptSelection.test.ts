import { beforeEach, describe, expect, it } from 'vitest'
import { forgetKeptSelection, keepSelection, keptSelection } from './keptSelection.ts'

const picked = { seats: ['a1', 'b1'], generalAdmission: { floor: 2 } }

beforeEach(() => sessionStorage.clear())

describe('kept selection', () => {
  it('brings back the selection kept for a Show', () => {
    keepSelection('show-1', picked)

    expect(keptSelection('show-1')).toEqual(picked)
  })

  it('is only for the Show it was kept for', () => {
    keepSelection('show-1', picked)

    expect(keptSelection('show-2')).toBeUndefined()
  })

  it('keeps one Show at a time', () => {
    keepSelection('show-1', picked)
    keepSelection('show-2', { seats: ['c1'], generalAdmission: {} })

    expect(keptSelection('show-1')).toBeUndefined()
    expect(keptSelection('show-2')).toEqual({ seats: ['c1'], generalAdmission: {} })
  })

  it('stays until it is forgotten', () => {
    keepSelection('show-1', picked)
    expect(keptSelection('show-1')).toEqual(picked)
    expect(keptSelection('show-1')).toEqual(picked)

    forgetKeptSelection()
    expect(keptSelection('show-1')).toBeUndefined()
  })

  it.each([
    ['not JSON', '{'],
    ['no selection', JSON.stringify({ showId: 'show-1' })],
    ['a Seat that is not an id', JSON.stringify({ showId: 'show-1', selection: { seats: [7], generalAdmission: {} } })],
    ['a quantity that is not a count', JSON.stringify({ showId: 'show-1', selection: { seats: [], generalAdmission: { floor: 1.5 } } })],
    ['nothing picked', JSON.stringify({ showId: 'show-1', selection: { seats: [], generalAdmission: {} } })],
    ['more than one Hold can take', JSON.stringify({ showId: 'show-1', selection: { seats: [], generalAdmission: { floor: 11 } } })],
  ])('ignores a kept selection with %s', (_, stored) => {
    sessionStorage.setItem('getmyseat.keptSelection', stored)

    expect(keptSelection('show-1')).toBeUndefined()
  })
})
