import { describe, expect, it } from 'vitest'
import { formatPrice, formatShowTime } from './format.ts'

describe('formatPrice', () => {
  it.each([
    [50000, '₹500'],
    [150000, '₹1,500'],
    [12345600, '₹1,23,456'],
    [49950, '₹499.50'],
    [0, '₹0'],
  ])('shows %i paise as %s', (amountPaise, expected) => {
    expect(formatPrice(amountPaise)).toBe(expected)
  })
})

describe('formatShowTime', () => {
  it('shows the start time in the Venue’s time zone', () => {
    expect(formatShowTime('2026-10-03T14:00:00Z', 'Asia/Kolkata')).toBe('Sat, 3 Oct, 2026, 7:30 pm IST')
  })

  it('follows the Venue, not the visitor, across a date line', () => {
    expect(formatShowTime('2026-10-03T20:00:00Z', 'Asia/Kolkata')).toBe('Sun, 4 Oct, 2026, 1:30 am IST')
    expect(formatShowTime('2026-10-03T20:00:00Z', 'Europe/London')).toMatch(/^Sat, 3 Oct, 2026, 9:00 pm /)
  })
})
