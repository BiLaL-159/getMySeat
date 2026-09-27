import type { SectionDetail, ShowAvailability } from '@/api/shows.ts'

// What the map knows about a Seat: free, held by someone else, or not yet (or no longer) known.
export type SeatState = 'available' | 'held' | 'unknown'

export type MapSeat = { id: string; number: number; label: string; state: SeatState }
export type MapRow = { label: string; seats: MapSeat[] }

export type MapSection =
  | { id: string; name: string; kind: 'SEATED'; rows: MapRow[] }
  // `available` is undefined while availability is unknown.
  | { id: string; name: string; kind: 'GENERAL_ADMISSION'; capacity: number; available: number | undefined }

// A layout Section, from a Show or (read-only, for review) a Venue.
type LayoutSection = Pick<SectionDetail, 'id' | 'name' | 'kind' | 'capacity' | 'seats'>

// The seat map: Sections in layout order, each Seated Section's rows in the order the layout
// first names them with Seats sorted by number, and whatever availability says is left.
export function buildSeatMap(sections: LayoutSection[], availability: ShowAvailability | undefined): MapSection[] {
  const seatAvailable = new Map<string, boolean>()
  const placesAvailable = new Map<string, number>()
  for (const section of availability?.sections ?? []) {
    for (const seat of section.seats ?? []) seatAvailable.set(seat.id!, !!seat.available)
    if (section.available != null) placesAvailable.set(section.id!, section.available)
  }

  return sections.map((section): MapSection => {
    const id = section.id!
    const name = section.name ?? ''
    if (section.kind === 'GENERAL_ADMISSION') {
      return { id, name, kind: 'GENERAL_ADMISSION', capacity: section.capacity ?? 0, available: placesAvailable.get(id) }
    }
    const rows = new Map<string, MapSeat[]>()
    for (const seat of section.seats ?? []) {
      const available = seatAvailable.get(seat.id!)
      const state: SeatState = available == null ? 'unknown' : available ? 'available' : 'held'
      const row = seat.row ?? ''
      rows.set(row, [...(rows.get(row) ?? []), { id: seat.id!, number: seat.number!, label: seat.label ?? '', state }])
    }
    return {
      id,
      name,
      kind: 'SEATED',
      rows: [...rows].map(([label, seats]) => ({ label, seats: seats.sort((a, b) => a.number - b.number) })),
    }
  })
}
