import { useId, type ReactNode } from 'react'
import { cn } from '@/lib/utils.ts'
import type { MapRow, MapSection, SeatState } from './seatMap.ts'

const seatStateNames: Record<SeatState, string> = {
  available: 'available',
  held: 'held by someone else',
  unknown: 'not known yet',
}

const seatStateClasses: Record<SeatState, string> = {
  available: '[&>rect]:fill-lime [&>rect]:stroke-ink [&>text]:fill-primary-foreground',
  held: '[&>rect]:fill-muted [&>rect]:stroke-border [&>text]:fill-muted-foreground',
  unknown: '[&>rect]:fill-none [&>rect]:stroke-border [&>rect]:[stroke-dasharray:3_2] [&>text]:fill-muted-foreground',
}

// One Seat's square, and the gap to the next.
const cell = 28
const seatSize = 22

// A read-only seat map: a stage, then each Section in the order given. `status` says what's
// known about availability, above the Sections.
function SeatMap({ sections, status }: { sections: MapSection[]; status?: ReactNode }) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-6">
      <h2 id={headingId} className="font-display text-2xl font-black uppercase">Seat map</h2>
      {status}
      <div className="rounded-md bg-ink py-2 text-center font-mono text-xs uppercase tracking-[0.3em] text-paper">Stage</div>
      <Legend />
      {sections.map((section) =>
        section.kind === 'SEATED' ? (
          <SeatedSection key={section.id} name={section.name} rows={section.rows} />
        ) : (
          <GeneralAdmissionSection key={section.id} {...section} />
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

function SeatedSection({ name, rows }: { name: string; rows: MapRow[] }) {
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
                  <g
                    key={seat.id}
                    role="img"
                    aria-label={`${name}, row ${row.label}, seat ${seat.number}, ${seatStateNames[seat.state]}`}
                    tabIndex={0}
                    className={cn(
                      'outline-none [&:focus-visible>rect]:stroke-violet [&:focus-visible>rect]:stroke-[3]',
                      seatStateClasses[seat.state],
                    )}
                  >
                    <rect
                      x={left + s * cell + (cell - seatSize) / 2}
                      y={y + (cell - seatSize) / 2}
                      width={seatSize}
                      height={seatSize}
                      rx={5}
                      strokeWidth={1.3}
                    />
                    <text x={left + s * cell + cell / 2} y={y + cell / 2} textAnchor="middle" dominantBaseline="central" fontSize={10} aria-hidden>
                      {seat.number}
                    </text>
                  </g>
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

function RowLabel({ x, y, label }: { x: number; y: number; label: string }) {
  return (
    <text x={x} y={y + cell / 2} textAnchor="middle" dominantBaseline="central" fontSize={11} className="fill-muted-foreground" aria-hidden>
      {label}
    </text>
  )
}

function GeneralAdmissionSection({ name, capacity, available }: { name: string; capacity: number; available: number | undefined }) {
  return (
    <SectionFrame name={name}>
      <div className="w-full max-w-md rounded-md border border-dashed px-4 py-6 text-center font-mono">
        {available == null
          ? `${capacity} places`
          : available === 0
            ? <strong className="text-destructive">Sold out</strong>
            : `${available} of ${capacity} places left`}
      </div>
    </SectionFrame>
  )
}

function Legend() {
  return (
    <ul aria-label="Key" className="flex flex-wrap justify-center gap-4 font-mono text-xs text-muted-foreground">
      {(['available', 'held'] as const).map((state) => (
        <li key={state} className="flex items-center gap-2">
          <svg width={14} height={14} aria-hidden className={seatStateClasses[state]}>
            <rect x={1} y={1} width={12} height={12} rx={3} strokeWidth={1.3} />
          </svg>
          {state === 'available' ? 'Available' : 'Held by someone else'}
        </li>
      ))}
    </ul>
  )
}

export default SeatMap
