import { maxTickets, ticketCount, type Selection } from './selection.ts'

// A selection kept across the trip to sign in and back, for one Show at a time. It lives in
// sessionStorage, so it's only for this tab, and the Show page forgets it once it's brought back.
export const storageKey = 'getmyseat.keptSelection'

// Keeps the selection for this Show, in place of any kept before.
export function keepSelection(showId: string, selection: Selection) {
  try {
    sessionStorage.setItem(storageKey, JSON.stringify({ showId, selection }))
  } catch {
    // Storage is off or full: the visitor signs in and picks again.
  }
}

// The selection kept for this Show, if there is one that could still make a Hold.
export function keptSelection(showId: string): Selection | undefined {
  let kept: unknown
  try {
    kept = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null')
  } catch {
    return undefined
  }
  if (!isObject(kept) || kept.showId !== showId || !isSelection(kept.selection)) return undefined
  const count = ticketCount(kept.selection)
  return count > 0 && count <= maxTickets ? kept.selection : undefined
}

// Forgets whatever was kept, once it's back on the page.
export function forgetKeptSelection() {
  try {
    sessionStorage.removeItem(storageKey)
  } catch {
    // Nothing was kept.
  }
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isSelection(value: unknown): value is Selection {
  return (
    isObject(value) &&
    Array.isArray(value.seats) &&
    value.seats.every((id) => typeof id === 'string') &&
    isObject(value.generalAdmission) &&
    Object.values(value.generalAdmission).every((quantity) => Number.isInteger(quantity) && (quantity as number) > 0)
  )
}
