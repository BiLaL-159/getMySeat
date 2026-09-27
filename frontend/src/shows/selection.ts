import type { Lost } from '@/api/problem.ts'
import type { MapSection } from './seatMap.ts'

// The most tickets one Hold can take, across Seats and General Admission.
export const maxTickets = 10

// What a visitor has picked at a Show: Seats by id, in the order picked, and a quantity for each
// General Admission Section they want places in.
export type Selection = { seats: string[]; generalAdmission: Record<string, number> }

export const emptySelection: Selection = { seats: [], generalAdmission: {} }

export function ticketCount(selection: Selection) {
  return selection.seats.length + Object.values(selection.generalAdmission).reduce((sum, quantity) => sum + quantity, 0)
}

// Whether the selection has as many tickets as one Hold can take.
export function isFull(selection: Selection) {
  return ticketCount(selection) >= maxTickets
}

function availableSeatIds(sections: MapSection[]) {
  return new Set(
    sections.flatMap((section) =>
      section.kind === 'SEATED' ? section.rows.flatMap((row) => row.seats.filter((seat) => seat.state === 'available').map((seat) => seat.id)) : [],
    ),
  )
}

function placesLeft(sections: MapSection[], sectionId: string) {
  const section = sections.find((s) => s.id === sectionId)
  return section?.kind === 'GENERAL_ADMISSION' ? (section.available ?? 0) : 0
}

// A Seat picked, or let go. Only an available Seat can be picked, and not once the selection is full.
export function toggleSeat(selection: Selection, seatId: string, sections: MapSection[]): Selection {
  if (selection.seats.includes(seatId)) return { ...selection, seats: selection.seats.filter((id) => id !== seatId) }
  if (!availableSeatIds(sections).has(seatId) || isFull(selection)) return selection
  return { ...selection, seats: [...selection.seats, seatId] }
}

// How many places to take in a General Admission Section, kept within the places left and the
// room the rest of the selection leaves.
export function setGeneralAdmission(selection: Selection, sectionId: string, quantity: number, sections: MapSection[]): Selection {
  const current = selection.generalAdmission[sectionId] ?? 0
  const room = maxTickets - (ticketCount(selection) - current)
  const capped = Math.max(0, Math.min(quantity, placesLeft(sections, sectionId), room))
  if (capped === current) return selection
  const { [sectionId]: _, ...others } = selection.generalAdmission
  return { ...selection, generalAdmission: capped === 0 ? others : { ...others, [sectionId]: capped } }
}

// The selection after availability changes: Seats someone else took drop out, and General
// Admission quantities come down to the places left. Seats in the visitor's own Hold (`mine`) are
// theirs, not taken, so they stay. Unchanged, it's the same object.
export function pruneSelection(selection: Selection, sections: MapSection[], mine: ReadonlySet<string> = new Set()): Selection {
  const available = availableSeatIds(sections)
  const seats = selection.seats.filter((id) => available.has(id) || mine.has(id))
  const generalAdmission: Record<string, number> = {}
  for (const [sectionId, quantity] of Object.entries(selection.generalAdmission)) {
    const fits = Math.min(quantity, placesLeft(sections, sectionId))
    if (fits > 0) generalAdmission[sectionId] = fits
  }
  const changed =
    seats.length !== selection.seats.length ||
    Object.entries(selection.generalAdmission).some(([sectionId, quantity]) => generalAdmission[sectionId] !== quantity)
  return changed ? { seats, generalAdmission } : selection
}

// What a prune took out of the selection, in the shape of a Hold's losses: the Seats that dropped
// out, and the places left in each General Admission Section whose quantity came down.
export function goneFrom(before: Selection, after: Selection, sections: MapSection[]): Lost {
  const kept = new Set(after.seats)
  return {
    unavailableSeats: before.seats.filter((id) => !kept.has(id)),
    unavailableSections: Object.entries(before.generalAdmission)
      .filter(([sectionId, quantity]) => (after.generalAdmission[sectionId] ?? 0) < quantity)
      .map(([sectionId]) => ({ sectionId, available: placesLeft(sections, sectionId) })),
  }
}

// The selection after a Hold failed on inventory that's gone: the lost Seats drop out, and each
// General Admission shortfall comes down to what's left. Everything else stays, ready to try again.
export function dropLost(selection: Selection, { unavailableSeats, unavailableSections }: Lost): Selection {
  const lost = new Set(unavailableSeats)
  const generalAdmission = { ...selection.generalAdmission }
  for (const { sectionId, available } of unavailableSections) {
    const quantity = Math.min(generalAdmission[sectionId] ?? 0, available)
    if (quantity > 0) generalAdmission[sectionId] = quantity
    else delete generalAdmission[sectionId]
  }
  return { seats: selection.seats.filter((id) => !lost.has(id)), generalAdmission }
}

export type SelectionLine = { key: string; section: string; place: string; pricePaise: number; amountPaise: number }

// The selection as lines in map order, each with its Section Price, and what it all costs.
export function summarizeSelection(selection: Selection, sections: MapSection[], pricesPaise: Record<string, number>) {
  const picked = new Set(selection.seats)
  const lines: SelectionLine[] = []
  for (const section of sections) {
    const pricePaise = pricesPaise[section.id] ?? 0
    if (section.kind === 'GENERAL_ADMISSION') {
      const quantity = selection.generalAdmission[section.id]
      if (quantity) {
        lines.push({ key: section.id, section: section.name, place: `General Admission × ${quantity}`, pricePaise, amountPaise: pricePaise * quantity })
      }
      continue
    }
    for (const row of section.rows) {
      for (const seat of row.seats) {
        if (picked.has(seat.id)) {
          lines.push({ key: seat.id, section: section.name, place: `Row ${row.label}, seat ${seat.number}`, pricePaise, amountPaise: pricePaise })
        }
      }
    }
  }
  return { lines, totalPaise: lines.reduce((sum, line) => sum + line.amountPaise, 0) }
}
