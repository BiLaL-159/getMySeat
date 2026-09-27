import { useId, type KeyboardEvent, type ReactNode } from 'react'
import { Button } from '@/components/ui/button.tsx'
import { cn } from '@/lib/utils.ts'
import type { MapRow, MapSeat, MapSection, SeatState } from './seatMap.ts'
import type { Selection } from './selection.ts'

// How a Seat looks: what availability says, unless the visitor has picked it.
type SeatLook = SeatState | 'selected'

// How a Seat reads to a screen reader, and in the key.
const seatLookNames: Record<SeatLook, string> = {
  available: 'available',
  selected: 'selected',
  held: 'held by someone else',
  unknown: 'not known yet',
}

const seatLookClasses: Record<SeatLook, string> = {
  available: '[&>rect]:fill-lime [&>rect]:stroke-ink [&>text]:fill-primary-foreground',
  selected: '[&>rect]:fill-ink [&>rect]:stroke-ink [&>text]:fill-lime [&>text]:font-bold',
  held: '[&>rect]:fill-muted [&>rect]:stroke-border [&>text]:fill-muted-foreground',
  unknown: '[&>rect]:fill-none [&>rect]:stroke-border [&>rect]:[stroke-dasharray:3_2] [&>text]:fill-muted-foreground',
}

// One Seat's square, and the gap to the next.
const cell = 28
const seatSize = 22

// What a seat map needs to let a visitor pick tickets: what they've picked so far, whether that's
// as many as they can take, and what to do when they pick.
export type Picking = {
  selection: Selection
  full: boolean
  onToggleSeat: (seatId: string) => void
  onSetGeneralAdmission: (sectionId: string, quantity: number) => void
}

// A seat map: a stage, then each Section in the order given. `status` says what's known about
// availability, above the Sections. Read-only, unless `picking` is given.
function SeatMap({ sections, status, picking }: { sections: MapSection[]; status?: ReactNode; picking?: Picking }) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-6">
      <h2 id={headingId} className="font-display text-2xl font-black uppercase">Seat map</h2>
      {status}
      <div className="rounded-md bg-ink py-2 text-center font-mono text-xs uppercase tracking-[0.3em] text-paper">Stage</div>
      <Legend showSelected={!!picking} />
      {sections.map((section) =>
        section.kind === 'SEATED' ? (
          <SeatedSection key={section.id} name={section.name} rows={section.rows} picking={picking} />
        ) : (
          <GeneralAdmissionSection key={section.id} {...section} picking={picking} />
        ),
      )}
    </section>
  )
}

function SectionFrame({ name, children }: { name: string; children: ReactNode }) {
  const headingId = useId()
  return (
    <div role="group" aria-labelledby={headingId} className="flex flex-col items-center gap-2">
      <h3 id={headingId} className="font-semibold">{name}</h3>
      {children}
    </div>
  )
}

function SeatedSection({ name, rows, picking }: { name: string; rows: MapRow[]; picking: Picking | undefined }) {
  if (rows.length === 0) {
    return (
      <SectionFrame name={name}>
        <p className="font-mono text-sm text-muted-foreground">No Seats</p>
      </SectionFrame>
    )
  }
  // A row label on each side, so a long row reads from either end.
  const width = (Math.max(...rows.map((row) => row.seats.length)) + 2) * cell
  const height = rows.length * cell
  return (
    <SectionFrame name={name}>
      <div className="max-w-full overflow-x-auto">
        <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="font-mono">
          {rows.map((row, r) => {
            const y = r * cell
            // Rows are centred, so shorter ones sit in the middle as in a hall.
            const left = (width - row.seats.length * cell) / 2
            return (
              <g key={row.label}>
                <RowLabel x={left - cell / 2} y={y} label={row.label} />
                {row.seats.map((seat, s) => (
                  <Seat key={seat.id} seat={seat} name={`${name}, row ${row.label}, seat ${seat.number}`} x={left + s * cell} y={y} picking={picking} />
                ))}
                <RowLabel x={left + row.seats.length * cell + cell / 2} y={y} label={row.label} />
              </g>
            )
          })}
        </svg>
      </div>
    </SectionFrame>
  )
}

// One Seat's square at (x, y). While picking, it's a checkbox: an available Seat can be picked
// until the selection is full, and a picked one let go.
function Seat({ seat, name, x, y, picking }: { seat: MapSeat; name: string; x: number; y: number; picking: Picking | undefined }) {
  const selected = !!picking?.selection.seats.includes(seat.id)
  const look: SeatLook = selected ? 'selected' : seat.state
  const pickable = selected || (seat.state === 'available' && !picking?.full)
  const toggle = () => {
    if (pickable) picking?.onToggleSeat(seat.id)
  }
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === ' ' || event.key === 'Enter') {
      event.preventDefault()
      toggle()
    }
  }
  const seatProps = picking
    ? { role: 'checkbox', 'aria-checked': selected, 'aria-disabled': !pickable, onClick: toggle, onKeyDown }
    : { role: 'img' }
  return (
    <g
      {...seatProps}
      aria-label={`${name}, ${seatLookNames[look]}`}
      tabIndex={0}
      className={cn(
        'outline-none [&:focus-visible>rect]:stroke-violet [&:focus-visible>rect]:stroke-[3]',
        picking && pickable && 'cursor-pointer',
        // At the limit, a free Seat can't be picked, so it fades.
        picking && !pickable && seat.state === 'available' && 'opacity-40',
        seatLookClasses[look],
      )}
    >
      <rect x={x + (cell - seatSize) / 2} y={y + (cell - seatSize) / 2} width={seatSize} height={seatSize} rx={5} strokeWidth={1.3} />
      <text x={x + cell / 2} y={y + cell / 2} textAnchor="middle" dominantBaseline="central" fontSize={10} aria-hidden>
        {seat.number}
      </text>
    </g>
  )
}

function RowLabel({ x, y, label }: { x: number; y: number; label: string }) {
  return (
    <text x={x} y={y + cell / 2} textAnchor="middle" dominantBaseline="central" fontSize={11} className="fill-muted-foreground" aria-hidden>
      {label}
    </text>
  )
}

function GeneralAdmissionSection({
  id,
  name,
  capacity,
  available,
  picking,
}: {
  id: string
  name: string
  capacity: number
  available: number | undefined
  picking: Picking | undefined
}) {
  const quantity = picking?.selection.generalAdmission[id] ?? 0
  return (
    <SectionFrame name={name}>
      <div className="flex w-full max-w-md flex-col items-center gap-3 rounded-md border border-dashed px-4 py-6 text-center font-mono">
        {available == null
          ? `${capacity} places`
          : available === 0
            ? <strong className="text-destructive">Sold out</strong>
            : `${available} of ${capacity} places left`}
        {picking && !!available && (
          <div className="flex items-center gap-3">
            <Button
              variant="outline"
              size="icon-sm"
              aria-label={`Fewer ${name} tickets`}
              disabled={quantity === 0}
              onClick={() => picking.onSetGeneralAdmission(id, quantity - 1)}
            >
              −
            </Button>
            <output role="status" aria-label={`${name} tickets`} className="min-w-8 text-lg font-bold">{quantity}</output>
            <Button
              variant="outline"
              size="icon-sm"
              aria-label={`More ${name} tickets`}
              disabled={quantity >= available || picking.full}
              onClick={() => picking.onSetGeneralAdmission(id, quantity + 1)}
            >
              +
            </Button>
          </div>
        )}
      </div>
    </SectionFrame>
  )
}

function Legend({ showSelected }: { showSelected: boolean }) {
  const looks: SeatLook[] = showSelected ? ['available', 'selected', 'held'] : ['available', 'held']
  return (
    <ul aria-label="Key" className="flex flex-wrap justify-center gap-4 font-mono text-xs text-muted-foreground">
      {looks.map((state) => (
        <li key={state} className="flex items-center gap-2">
          <svg width={14} height={14} aria-hidden className={seatLookClasses[state]}>
            <rect x={1} y={1} width={12} height={12} rx={3} strokeWidth={1.3} />
          </svg>
          <span className="first-letter:uppercase">{seatLookNames[state]}</span>
        </li>
      ))}
    </ul>
  )
}

export default SeatMap
