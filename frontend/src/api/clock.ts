// When a Hold ends by this device's clock. The API's `expiresAt` is by its own clock, which the
// response's Date header tells us, so it moves by however far this device's clock was from the API's
// when the answer arrived. Without a readable Date header, this device's clock is all there is.
export function localExpiry(expiresAt: string, dateHeader: string | null, receivedAt: number) {
  const apiNow = Date.parse(dateHeader ?? '')
  const offset = Number.isNaN(apiNow) ? 0 : receivedAt - apiNow
  return Date.parse(expiresAt) + offset
}
