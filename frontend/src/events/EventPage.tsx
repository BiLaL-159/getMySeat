import { Link, useParams, useSearchParams } from 'react-router'
import { eventCategoryLabels, useEvent, useEventShows, type EventResponse } from '@/api/events.ts'
import { ApiError } from '@/api/problem.ts'
import MessageCard from '@/app/MessageCard.tsx'
import { problemMessage } from '@/app/problemMessage.ts'
import { Button } from '@/components/ui/button.tsx'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card.tsx'
import { formatShowTime } from '@/shows/format.ts'

// An Event for anyone to see: what it is, and each upcoming Show to pick from.
function EventPage() {
  const { id } = useParams() as { id: string }
  const event = useEvent(id)

  if (event.isPending) return <p className="font-mono text-muted-foreground">Loading the Event…</p>
  if (event.isError) {
    if (event.error instanceof ApiError && event.error.problem.kind === 'not-found') {
      return (
        <MessageCard title="Event not found" description="There's no Event at this address. It may never have been published.">
          <Link to="/" reloadDocument className="underline">Back to getMySeat</Link>
        </MessageCard>
      )
    }
    return (
      <div className="flex flex-col items-start gap-4">
        <p role="alert" className="text-destructive">We couldn&apos;t load this Event. {problemMessage(event.error)}</p>
        <Button variant="outline" onClick={() => void event.refetch()}>Try again</Button>
      </div>
    )
  }
  return <EventDetails event={event.data} />
}

const languageNames = new Intl.DisplayNames('en', { type: 'language' })

// Events carry an ISO 639-1 code; show its English name, or the code if it isn't one we know.
function languageName(code: string) {
  try {
    return languageNames.of(code) ?? code
  } catch {
    return code
  }
}

function EventDetails({ event }: { event: EventResponse }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h1 className="font-display text-4xl font-black uppercase leading-none">{event.title}</h1>
        </CardTitle>
        <CardDescription className="flex gap-3 font-mono">
          {event.category && <span>{eventCategoryLabels[event.category]}</span>}
          {event.language && <span>{languageName(event.language)}</span>}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <p className="whitespace-pre-line">{event.description}</p>
        <UpcomingShows eventId={event.id!} />
      </CardContent>
    </Card>
  )
}

function UpcomingShows({ eventId }: { eventId: string }) {
  // The address carries the page, one-based, so a page of Shows can be shared or reloaded.
  const [searchParams] = useSearchParams()
  const requested = Number(searchParams.get('page'))
  const page = Number.isInteger(requested) && requested > 1 ? requested : 1
  const shows = useEventShows(eventId, page - 1)

  return (
    <section aria-labelledby="upcoming-shows" className="flex flex-col gap-3">
      <h2 id="upcoming-shows" className="font-display text-2xl font-black uppercase">Upcoming Shows</h2>
      {shows.isPending ? (
        <p className="font-mono text-muted-foreground">Loading the Shows…</p>
      ) : shows.isError ? (
        <div className="flex flex-col items-start gap-4">
          <p role="alert" className="text-destructive">We couldn&apos;t load the Shows. {problemMessage(shows.error)}</p>
          <Button variant="outline" onClick={() => void shows.refetch()}>Try again</Button>
        </div>
      ) : !shows.data.content?.length ? (
        <p className="text-muted-foreground">There are no upcoming Shows of this Event.</p>
      ) : (
        <>
          <ul aria-label="Shows" className="divide-y">
            {shows.data.content.map((show) => (
              <li key={show.id} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-3">
                <Link to={`/shows/${show.id}`} className="font-mono hover:underline">
                  {show.startsAt && show.venue?.timeZone ? (
                    <time dateTime={show.startsAt}>{formatShowTime(show.startsAt, show.venue.timeZone)}</time>
                  ) : (
                    'See this Show'
                  )}
                </Link>
                <span>
                  <span className="font-semibold">{show.venue?.name}</span>{' '}
                  <span className="text-muted-foreground">{show.venue?.city}</span>
                </span>
              </li>
            ))}
          </ul>
          <Paging page={page} totalPages={shows.data.page?.totalPages ?? 1} />
        </>
      )}
    </section>
  )
}

function Paging({ page, totalPages }: { page: number; totalPages: number }) {
  if (totalPages <= 1) return null
  return (
    <nav aria-label="Pages of Shows" className="flex items-center gap-4 font-mono text-sm">
      {page > 1 && (
        <Button variant="outline" size="sm" asChild>
          <Link to={page === 2 ? '.' : `?page=${page - 1}`}>Previous</Link>
        </Button>
      )}
      <span>Page {page} of {totalPages}</span>
      {page < totalPages && (
        <Button variant="outline" size="sm" asChild>
          <Link to={`?page=${page + 1}`}>Next</Link>
        </Button>
      )}
    </nav>
  )
}

export default EventPage
