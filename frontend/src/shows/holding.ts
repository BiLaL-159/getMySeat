import type { HoldRequest } from '@/api/holds.ts'
import type { Lost } from '@/api/problem.ts'
import type { MapSection } from './seatMap.ts'
import type { Selection } from './selection.ts'

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

// What was lost, in map order, for telling the visitor.
export function describeLoss({ unavailableSeats, unavailableSections }: Lost, sections: MapSection[]) {
  const lostSeats = new Set(unavailableSeats)
  const placesLeft = new Map(unavailableSections.map(({ sectionId, available }) => [sectionId, available]))
  const lines: string[] = []
  for (const section of sections) {
    if (section.kind === 'GENERAL_ADMISSION') {
      const left = placesLeft.get(section.id)
      if (left != null) lines.push(`${section.name}: ${placesLeftNote(left)}`)
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

// How many places a General Admission Section had left when a Hold fell short on it.
export function placesLeftNote(left: number) {
  return left === 0 ? 'sold out' : `only ${left} ${left === 1 ? 'place' : 'places'} left`
}

// The selection once a Hold has taken what it asked for. Anything picked while it was on its way stays.
export function withoutHeld(selection: Selection, request: HoldRequest): Selection {
  const held = new Set(request.seats)
  const generalAdmission = { ...selection.generalAdmission }
  for (const { sectionId, quantity } of request.generalAdmission ?? []) {
    const left = (generalAdmission[sectionId] ?? 0) - quantity
    if (left > 0) generalAdmission[sectionId] = left
    else delete generalAdmission[sectionId]
  }
  return { seats: selection.seats.filter((id) => !held.has(id)), generalAdmission }
}
