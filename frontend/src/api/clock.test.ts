import { describe, expect, it } from 'vitest'
import { localExpiry } from './clock.ts'

describe('localExpiry', () => {
  const receivedAt = Date.parse('2026-10-03T13:00:00Z')

  it('is expiresAt itself when the API and this device agree on the time', () => {
    expect(localExpiry('2026-10-03T13:10:00Z', 'Sat, 03 Oct 2026 13:00:00 GMT', receivedAt)).toBe(Date.parse('2026-10-03T13:10:00Z'))
  })

  it('comes earlier on a device whose clock is ahead of the API', () => {
    // The device reads 13:00:00 when the API says 12:58:00, so its clock is 2 minutes fast.
    expect(localExpiry('2026-10-03T13:08:00Z', 'Sat, 03 Oct 2026 12:58:00 GMT', receivedAt)).toBe(Date.parse('2026-10-03T13:10:00Z'))
  })

  it('comes later on a device whose clock is behind the API', () => {
    expect(localExpiry('2026-10-03T13:13:00Z', 'Sat, 03 Oct 2026 13:03:00 GMT', receivedAt)).toBe(Date.parse('2026-10-03T13:10:00Z'))
  })

  it.each([null, '', 'not a date'])('trusts this device when the Date header is %j', (date) => {
    expect(localExpiry('2026-10-03T13:10:00Z', date, receivedAt)).toBe(Date.parse('2026-10-03T13:10:00Z'))
  })
})
