import { ApiError, isRetryable, type UnavailableSection } from '@/api/problem.ts'
import type { components } from '@/api/schema'
import type { MapSection } from './seatMap.ts'
import type { Selection } from './selection.ts'

export type HoldRequest = components['schemas']['HoldRequest']

// What an inventory-unavailable problem says is gone.
export type Lost = { unavailableSeats: string[]; unavailableSections: UnavailableSection[] }

// The selection as a Hold request, sorted so the same tickets always make the same request.
export function holdRequest(selection: Selection): HoldRequest {
  return {
    seats: [...selection.seats].sort(),
    generalAdmission: Object.entries(selection.generalAdmission)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([sectionId, quantity]) => ({ sectionId, quantity })),
  }
}

// One Idempotency-Key per attempt at a Hold. Asking for the same tickets again (a retry after a
// conflict or a network failure) reuses it, so the Hold is never made twice; different tickets get
// a new one. `forget` ends the attempt, once it has made its Hold.
export function createAttemptKeys(newKey: () => string = () => crypto.randomUUID()) {
  let attempt: { request: string; key: string } | undefined
  return {
    keyFor(request: HoldRequest) {
      const asked = JSON.stringify(request)
      if (attempt?.request !== asked) attempt = { request: asked, key: newKey() }
      return attempt.key
    },
    forget() {
      attempt = undefined
    },
  }
}

const maxHoldRetries = 3

// Whether a failed Hold is worth sending again with the same key: nothing answered, the server
// failed, or it lost a race with other Holds (a plain conflict, or inventory-unavailable naming
// nothing). Anything actually gone won't come back by asking again.
export function shouldRetryHold(failures: number, error: unknown) {
  if (failures >= maxHoldRetries || !(error instanceof ApiError)) return false
  const { problem } = error
  if (problem.kind === 'inventory-unavailable') return problem.unavailableSeats.length === 0 && problem.unavailableSections.length === 0
  return problem.kind === 'conflict' || isRetryable(error)
}

// What was lost, in map order, for telling the visitor.
export function describeLoss({ unavailableSeats, unavailableSections }: Lost, sections: MapSection[]) {
  const lostSeats = new Set(unavailableSeats)
  const placesLeft = new Map(unavailableSections.map(({ sectionId, available }) => [sectionId, available]))
  const lines: string[] = []
  for (const section of sections) {
    if (section.kind === 'GENERAL_ADMISSION') {
      const left = placesLeft.get(section.id)
      if (left != null) lines.push(`${section.name}: ${left === 0 ? 'sold out' : `only ${left} ${left === 1 ? 'place' : 'places'} left`}`)
      continue
    }
    for (const row of section.rows) {
      for (const seat of row.seats) {
        if (lostSeats.has(seat.id)) lines.push(`${section.name}, row ${row.label}, seat ${seat.number}`)
      }
    }
  }
  return lines
}
