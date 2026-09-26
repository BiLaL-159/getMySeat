import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { useEvent } from '@/api/events.ts'
import { ApiError } from '@/api/problem.ts'
import { sectionKindLabels, useShow, type ShowDetail } from '@/api/shows.ts'
import MessageCard from '@/app/MessageCard.tsx'
import { problemMessage } from '@/app/problemMessage.ts'
import { Button } from '@/components/ui/button.tsx'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card.tsx'
import { formatPrice, formatShowTime } from './format.ts'

// A Show for anyone to see: where and when it happens, and what each Section costs.
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
      </CardContent>
    </Card>
  )
}

export default ShowPage
