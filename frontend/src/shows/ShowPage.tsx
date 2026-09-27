import { useId, useMemo, useState, type ReactNode } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { useEvent } from '@/api/events.ts'
import { useCreateHold, useMyHold, type Hold } from '@/api/holds.ts'
import { ApiError, isRace, type Lost } from '@/api/problem.ts'
import { sectionKindLabels, useShow, useShowAvailability, type ShowDetail } from '@/api/shows.ts'
import MessageCard from '@/app/MessageCard.tsx'
import { problemMessage } from '@/app/problemMessage.ts'
import { useSession } from '@/auth/session.ts'
import { Button } from '@/components/ui/button.tsx'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card.tsx'
import { formatPrice, formatShowTime } from './format.ts'
import { createAttemptKeys, describeLoss, holdRequest, withoutHeld } from './holding.ts'
import { forgetKeptSelection, keepSelection, keptSelection } from './keptSelection.ts'
import SeatMap from './SeatMap.tsx'
import { buildSeatMap, type MapSection } from './seatMap.ts'
import {
  dropLost,
  emptySelection,
  goneFrom,
  maxTickets,
  pruneSelection,
  setGeneralAdmission,
  summarizeSelection,
  isFull,
  ticketCount,
  toggleSeat,
  type Selection,
} from './selection.ts'

// A Show for anyone to see: where and when it happens, what each Section costs, and what's left.
function ShowPage() {
  const { id } = useParams() as { id: string }
  const show = useShow(id)

  if (show.isPending) return <p className="font-mono text-muted-foreground">Loading the Show…</p>
  if (show.isError) {
    if (show.error instanceof ApiError && show.error.problem.kind === 'not-found') {
      return (
        <MessageCard title="Show not found" description="There's no Show at this address. It may never have been published.">
          <Link to="/" reloadDocument className="underline">Back to getMySeat</Link>
        </MessageCard>
      )
    }
    return (
      <div className="flex flex-col items-start gap-4">
        <p role="alert" className="text-destructive">We couldn&apos;t load this Show. {problemMessage(show.error)}</p>
        <Button variant="outline" onClick={() => void show.refetch()}>Try again</Button>
      </div>
    )
  }
  return <ShowDetails show={show.data} />
}

function ShowDetails({ show }: { show: ShowDetail }) {
  const event = useEvent(show.eventId)
  const { venue, startsAt } = show
  // Read once, when the page opens.
  const [openedAt] = useState(() => Date.now())
  const started = !!startsAt && Date.parse(startsAt) <= openedAt

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h1 className="font-display text-4xl font-black uppercase leading-none">
            <Link to={`/events/${show.eventId}`} className="hover:underline">{event.data?.title ?? 'The Event'}</Link>
          </h1>
        </CardTitle>
        <CardDescription className="flex flex-col gap-1">
          {startsAt && venue?.timeZone && (
            <time dateTime={startsAt} className="font-mono text-foreground">{formatShowTime(startsAt, venue.timeZone)}</time>
          )}
          {started && <strong className="font-mono text-destructive">This Show has already started.</strong>}
          <span className="font-semibold text-foreground">{venue?.name}</span>
          <span>{[venue?.address, venue?.city].filter(Boolean).join(', ')}</span>
        </CardDescription>
      </CardHeader>
      <CardContent>
        <ul aria-label="Sections" className="divide-y">
          {show.sections?.map((section) => (
            <li key={section.id} className="flex items-baseline justify-between gap-4 py-3">
              <span>
                <span className="font-semibold">{section.name}</span>{' '}
                <span className="font-mono text-sm text-muted-foreground">{section.kind && sectionKindLabels[section.kind]}</span>
              </span>
              <span className="font-mono">{section.price?.amountPaise != null ? formatPrice(section.price.amountPaise) : 'Price not set'}</span>
            </li>
          ))}
        </ul>
        <ShowSeatMap show={show} started={started} />
      </CardContent>
    </Card>
  )
}

// The Show's seat map, kept up to date while its tickets are on sale, when a visitor can pick
// tickets on it and hold them. A draft Show has no inventory yet, so there's nothing to ask for.
function ShowSeatMap({ show, started }: { show: ShowDetail; started: boolean }) {
  const published = show.status === 'PUBLISHED'
  const onSale = published && !started
  const availability = useShowAvailability(show.id!, { enabled: published, live: !started })
  const sections = useMemo(() => buildSeatMap(show.sections ?? [], availability.data), [show.sections, availability.data])
  const myHold = useMyHold(show.id!, { enabled: published })
  const hold = myHold.data?.status === 'ACTIVE' ? myHold.data : undefined
  const myHoldSeats = useMemo(() => new Set(hold?.items?.flatMap((item) => (item.seatId ? [item.seatId] : []))), [hold])

  // A selection kept while the visitor went to sign in comes back, and is checked against the first
  // availability to arrive. The note says so, and what went meanwhile, until the visitor moves on.
  const [kept] = useState(() => keptSelection(show.id!))
  const [selection, setSelection] = useState(kept ?? emptySelection)
  const [keptNote, setKeptNote] = useState<{ gone?: Lost } | undefined>(kept && {})
  // Whatever someone else took since the last look drops out of the selection.
  const [lastPrunedSections, setLastPrunedSections] = useState(kept ? undefined : sections)
  if (lastPrunedSections !== sections && availability.data) {
    setLastPrunedSections(sections)
    const pruned = pruneSelection(selection, sections)
    setSelection(pruned)
    if (kept && !lastPrunedSections) setKeptNote({ gone: goneFrom(selection, pruned, sections) })
  }
  // The visitor changed what they picked, so there's nothing kept to bring back.
  const change = (next: (picked: Selection) => Selection) => {
    forgetKeptSelection()
    setKeptNote(undefined)
    setSelection(next)
  }

  const session = useSession()
  const location = useLocation()
  const createHold = useCreateHold(show.id!)
  const [attemptKeys] = useState(() => createAttemptKeys())
  // What the last Hold attempt lost to someone else, until the next attempt, with when availability
  // was last answered at the time.
  const [lost, setLost] = useState<{ gone: Lost; seenAt: number }>()
  // The last attempt brought back, under its key, a Hold that had already ended.
  const [ended, setEnded] = useState(false)
  const lostSeats = useMemo(() => {
    // A lost Seat that a newer answer says is free again can be picked again.
    const newer = lost?.seenAt !== availability.dataUpdatedAt
    const free = new Set(
      sections.flatMap((section) =>
        section.kind === 'SEATED' ? section.rows.flatMap((row) => row.seats.filter((seat) => seat.state === 'available').map((seat) => seat.id)) : [],
      ),
    )
    return new Set(lost?.gone.unavailableSeats.filter((id) => !(newer && free.has(id))))
  }, [lost, sections, availability.dataUpdatedAt])
  const lostSections = useMemo(
    () => new Map(lost?.gone.unavailableSections.map(({ sectionId, available }) => [sectionId, available])),
    [lost],
  )

  const onHold = () => {
    if (session.status !== 'signedIn') {
      keepSelection(show.id!, selection)
      session.signIn(location.pathname + location.search)
      return
    }
    const request = holdRequest(selection)
    const seenAt = availability.dataUpdatedAt
    setLost(undefined)
    setEnded(false)
    setKeptNote(undefined)
    createHold.mutate(
      { request, key: attemptKeys.keyFor(request) },
      {
        onSuccess: (made) => {
          attemptKeys.forget()
          if (made.status === 'ACTIVE') {
            forgetKeptSelection()
            setSelection((picked) => withoutHeld(picked, request))
          }
          else setEnded(true)
        },
        onError: (error) => {
          if (!(error instanceof ApiError)) return
          const { problem } = error
          // That key went with other tickets, so the next attempt needs a new one.
          if (problem.kind === 'idempotency-key-reused') attemptKeys.forget()
          if (problem.kind !== 'inventory-unavailable' || isRace(problem)) return
          const gone = { unavailableSeats: problem.unavailableSeats, unavailableSections: problem.unavailableSections }
          setLost({ gone, seenAt })
          setSelection((picked) => dropLost(picked, gone))
        },
      },
    )
  }

  let status
  if (!published) {
    status = <p className="font-mono text-sm text-muted-foreground">Availability shows here once the Show is published.</p>
  } else if (availability.isError) {
    status = (
      <p role="alert" className="text-destructive">
        {availability.data ? "We couldn't refresh what's left, so this may be out of date." : "We couldn't load what's left."}{' '}
        {problemMessage(availability.error)}{' '}
        <Button variant="link" className="h-auto p-0" onClick={() => void availability.refetch()}>Try again</Button>
      </p>
    )
  } else if (availability.isPending) {
    status = <p className="font-mono text-sm text-muted-foreground">Checking what&apos;s left…</p>
  } else if (started) {
    status = <p className="font-mono text-sm text-muted-foreground">Tickets are no longer on sale.</p>
  }

  const picking = onSale
    ? {
        selection,
        full: isFull(selection),
        lostSeats,
        lostSections,
        onToggleSeat: (seatId: string) => change((picked) => toggleSeat(picked, seatId, sections)),
        onSetGeneralAdmission: (sectionId: string, quantity: number) =>
          change((picked) => setGeneralAdmission(picked, sectionId, quantity, sections)),
      }
    : undefined

  let holdOutcome
  if (lost) {
    holdOutcome = (
      <div role="alert" className="flex flex-col gap-1 text-destructive">
        <p>Someone else got there first, so nothing was held. We kept the rest of your selection, ready to try again.</p>
        <ul className="list-inside list-disc font-mono text-sm">
          {describeLoss(lost.gone, sections).map((line) => <li key={line}>{line}</li>)}
        </ul>
      </div>
    )
  } else if (ended) {
    holdOutcome = (
      <p role="alert" className="text-destructive">
        Your earlier Hold on these tickets has already ended, so nothing is held now. Hold them again to try once more.
      </p>
    )
  } else if (createHold.isError) {
    holdOutcome = <p role="alert" className="text-destructive">We couldn&apos;t hold these tickets. {problemMessage(createHold.error)}</p>
  } else if (keptNote) {
    holdOutcome = <KeptNote gone={keptNote.gone} sections={sections} empty={ticketCount(selection) === 0} />
  }

  return (
    <div className="mt-8 flex flex-col gap-8">
      <SeatMap sections={sections} status={status} myHoldSeats={myHoldSeats} picking={picking} />
      {myHold.isError && (
        <p role="alert" className="text-destructive">We couldn&apos;t check for a Hold of yours. {problemMessage(myHold.error)}</p>
      )}
      {hold && <HoldPanel hold={hold} show={show} />}
      {onSale && (
        <SelectionSummary
          selection={selection}
          sections={sections}
          show={show}
          holding={createHold.isPending}
          outcome={holdOutcome}
          onHold={onHold}
        />
      )}
    </div>
  )
}

// Tells a visitor back from signing in that their selection was kept, and what of it went meanwhile.
function KeptNote({ gone, sections, empty }: { gone: Lost | undefined; sections: MapSection[]; empty: boolean }) {
  const lines = gone ? describeLoss(gone, sections) : []
  if (lines.length === 0) {
    return <p role="status" className="font-mono text-sm">We kept the tickets you picked before signing in. Hold them when you&apos;re ready.</p>
  }
  return (
    <div role="alert" className="flex flex-col gap-1 text-destructive">
      <p>
        {empty
          ? 'Everything you picked went while you were signing in, so there is nothing left to hold.'
          : 'We kept the tickets you picked before signing in, but some went while you were signing in:'}
      </p>
      <ul className="list-inside list-disc font-mono text-sm">
        {lines.map((line) => <li key={line}>{line}</li>)}
      </ul>
    </div>
  )
}

// What the visitor has picked, what it costs, and the way on to holding it.
function SelectionSummary({
  selection,
  sections,
  show,
  holding,
  outcome,
  onHold,
}: {
  selection: Selection
  sections: MapSection[]
  show: ShowDetail
  holding: boolean
  outcome: ReactNode
  onHold: () => void
}) {
  const headingId = useId()
  const pricesPaise = useMemo(
    () => Object.fromEntries((show.sections ?? []).map((section) => [section.id!, section.price?.amountPaise ?? 0])),
    [show.sections],
  )
  const { lines, totalPaise } = summarizeSelection(selection, sections, pricesPaise)

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-md border p-4">
      <h2 id={headingId} className="font-display text-2xl font-black uppercase">Your selection</h2>
      {lines.length === 0 ? (
        <p className="font-mono text-sm text-muted-foreground">Pick Seats on the map, or places in a General Admission Section.</p>
      ) : (
        <ul className="divide-y">
          {lines.map((line) => (
            <li key={line.key} className="flex items-baseline justify-between gap-4 py-2">
              <span>
                <span className="font-semibold">{line.section}</span> <span>{line.place}</span>
              </span>
              <span className="font-mono">
                {line.amountPaise !== line.pricePaise && (
                  <span className="text-sm text-muted-foreground">{formatPrice(line.pricePaise)} each · </span>
                )}
                {formatPrice(line.amountPaise)}
              </span>
            </li>
          ))}
        </ul>
      )}
      <p className="flex items-baseline justify-between font-mono text-lg">
        <span>Total</span>
        <strong>{formatPrice(totalPaise)}</strong>
      </p>
      {/* Always there, so a screen reader hears the limit when it's reached. */}
      <p role="status" className="font-mono text-sm text-destructive empty:hidden">
        {isFull(selection) && `That's the limit of ${maxTickets} tickets. Let one go to pick another.`}
      </p>
      {outcome}
      <Button disabled={ticketCount(selection) === 0 || holding} onClick={onHold} className="self-end">
        {holding ? 'Holding…' : 'Hold'}
      </Button>
    </section>
  )
}

// The visitor's Hold: what's in it, what it costs, and (once payments arrive) the way to pay.
function HoldPanel({ hold, show }: { hold: Hold; show: ShowDetail }) {
  const headingId = useId()
  const payNoteId = useId()
  const sectionNames = useMemo(
    () => new Map((show.sections ?? []).map((section) => [section.id, section.name])),
    [show.sections],
  )

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-md border-2 border-violet p-4">
      <h2 id={headingId} className="font-display text-2xl font-black uppercase">Your Hold</h2>
      <ul className="divide-y">
        {hold.items?.map((item) => {
          const quantity = item.quantity ?? 1
          const pricePaise = item.pricePaise ?? 0
          return (
            <li key={item.seatId ?? item.sectionId} className="flex items-baseline justify-between gap-4 py-2">
              <span>
                <span className="font-semibold">{sectionNames.get(item.sectionId)}</span>{' '}
                <span>{item.kind === 'SEAT' ? `Row ${item.rowLabel}, seat ${item.seatNumber}` : `General Admission × ${quantity}`}</span>
              </span>
              <span className="font-mono">
                {quantity > 1 && <span className="text-sm text-muted-foreground">{formatPrice(pricePaise)} each · </span>}
                {formatPrice(pricePaise * quantity)}
              </span>
            </li>
          )
        })}
      </ul>
      <p className="flex items-baseline justify-between font-mono text-lg">
        <span>Total</span>
        <strong>{formatPrice(hold.totalPaise ?? 0)}</strong>
      </p>
      <div className="flex items-center justify-end gap-3">
        <span id={payNoteId} className="font-mono text-sm text-muted-foreground">Payments arrive soon</span>
        <Button disabled aria-describedby={payNoteId}>Pay</Button>
      </div>
    </section>
  )
}

export default ShowPage
