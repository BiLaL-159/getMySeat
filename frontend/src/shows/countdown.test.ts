import { describe, expect, it } from 'vitest'
import { countdown } from './countdown.ts'

describe('countdown', () => {
  it.each([
    [10 * 60_000, '10:00', false],
    [9 * 60_000 + 5_000, '9:05', false],
    [60_001, '1:01', false],
    [60_000, '1:00', false],
    [59_999, '1:00', false],
    [59_001, '1:00', false],
    [59_000, '0:59', true],
    [1, '0:01', true],
  ])('shows %i ms left as %s', (msLeft, text, lastMinute) => {
    expect(countdown(msLeft)).toEqual({ text, lastMinute, over: false })
  })

  it.each([0, -1, -60_000])('is over at %i ms left', (msLeft) => {
    expect(countdown(msLeft)).toEqual({ text: '0:00', lastMinute: true, over: true })
  })
})
