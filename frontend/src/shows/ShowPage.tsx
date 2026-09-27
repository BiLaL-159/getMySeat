import { useId, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { useEvent } from '@/api/events.ts'
import { ApiError } from '@/api/problem.ts'
import { sectionKindLabels, useShow, useShowAvailability, type ShowDetail } from '@/api/shows.ts'
import MessageCard from '@/app/MessageCard.tsx'
import { problemMessage } from '@/app/problemMessage.ts'
import { Button } from '@/components/ui/button.tsx'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card.tsx'
import { formatPrice, formatShowTime } from './format.ts'
import SeatMap from './SeatMap.tsx'
import { buildSeatMap, type MapSection } from './seatMap.ts'
import {
  emptySelection,
  maxTickets,
  pruneSelection,
  setGeneralAdmission,
  summarizeSelection,
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
// tickets on it. A draft Show has no inventory yet, so there's nothing to ask for.
function ShowSeatMap({ show, started }: { show: ShowDetail; started: boolean }) {
  const published = show.status === 'PUBLISHED'
  const onSale = published && !started
  const availability = useShowAvailability(show.id!, { enabled: published, live: !started })
  const sections = useMemo(() => buildSeatMap(show.sections ?? [], availability.data), [show.sections, availability.data])

  const [selection, setSelection] = useState(emptySelection)
  // Whatever someone else took since the last look drops out of the selection.
  const [prunedFor, setPrunedFor] = useState(sections)
  if (prunedFor !== sections) {
    setPrunedFor(sections)
    setSelection(pruneSelection(selection, sections))
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
        full: ticketCount(selection) >= maxTickets,
        onToggleSeat: (seatId: string) => setSelection((picked) => toggleSeat(picked, seatId, sections)),
        onSetGeneralAdmission: (sectionId: string, quantity: number) =>
          setSelection((picked) => setGeneralAdmission(picked, sectionId, quantity, sections)),
      }
    : undefined

  return (
    <div className="mt-8 flex flex-col gap-8">
      <SeatMap sections={sections} status={status} picking={picking} />
      {onSale && <SelectionSummary selection={selection} sections={sections} show={show} />}
    </div>
  )
}

// What the visitor has picked, what it costs, and the way on to holding it.
function SelectionSummary({ selection, sections, show }: { selection: Selection; sections: MapSection[]; show: ShowDetail }) {
  const headingId = useId()
  const pricesPaise = useMemo(
    () => Object.fromEntries((show.sections ?? []).map((section) => [section.id!, section.price?.amountPaise ?? 0])),
    [show.sections],
  )
  const { lines, totalPaise } = summarizeSelection(selection, sections, pricesPaise)
  const count = ticketCount(selection)

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
      {count >= maxTickets && (
        <p role="status" className="font-mono text-sm text-destructive">
          That&apos;s the limit of {maxTickets} tickets. Let one go to pick another.
        </p>
      )}
      <Button disabled={count === 0} className="self-end">Hold</Button>
    </section>
  )
}

export default ShowPage
