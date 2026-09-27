import type { FormEvent, ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import { eventCategoryLabels, useEventSearch, type EventCard } from '@/api/events.ts'
import MessageCard from '@/app/MessageCard.tsx'
import { problemMessage } from '@/app/problemMessage.ts'
import { Button } from '@/components/ui/button.tsx'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card.tsx'
import { Input } from '@/components/ui/input.tsx'
import { cn } from '@/lib/utils.ts'
import { formatPrice, formatShowTime } from '@/shows/format.ts'
import { categoryArt } from './categoryArt.ts'
import { readSearch, sortLabels, toApiQuery, writeSearch, type EventSearch, type Sort } from './search.ts'

const selectClass =
  'h-9 w-full rounded-md border border-input bg-transparent px-3 text-base shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 md:text-sm dark:bg-input/30'

function eventsHref(search: EventSearch) {
  const query = writeSearch(search).toString()
  return query ? `/events?${query}` : '/events'
}

// Published Events for anyone to browse. The search lives in the URL, so it can be shared,
// bookmarked and reached from the landing page.
function EventsPage() {
  const [params, setParams] = useSearchParams()
  const search = readSearch(params)
  const results = useEventSearch(toApiQuery(search))

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const field = (name: string) => String(form.get(name) ?? '')
    // readSearch drops what the API would refuse, such as an end date before the start.
    setParams(writeSearch(readSearch(new URLSearchParams({
      q: field('q'),
      city: field('city'),
      category: field('category'),
      from: field('from'),
      to: field('to'),
      sort: search.sort,
    }))))
  }

  return (
    <div className="flex flex-col gap-6">
      <h1 className="font-display text-4xl font-black uppercase leading-none">Events</h1>
      {/* Keyed by the search so the fields follow the URL, as when the filters are cleared. */}
      <SearchForm key={params.toString()} search={search} onSubmit={onSubmit} />
      <label className="flex items-center gap-2 self-end font-mono text-sm">
        Sort
        <select
          className={`${selectClass} w-auto`}
          value={search.sort}
          onChange={(event) => setParams(writeSearch({ ...search, sort: event.target.value as Sort, page: 1 }))}
        >
          <Options labels={sortLabels} />
        </select>
      </label>
      <Results search={search} results={results} />
    </div>
  )
}

function Field({ label, className, children }: { label: string; className?: string; children: ReactNode }) {
  return (
    <label className={cn('flex flex-col gap-1 font-mono text-sm', className)}>
      {label}
      {children}
    </label>
  )
}

function Options({ labels }: { labels: Record<string, string> }) {
  return Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)
}

function SearchForm({ search, onSubmit }: { search: EventSearch; onSubmit: (event: FormEvent<HTMLFormElement>) => void }) {
  return (
    <form role="search" onSubmit={onSubmit} className="grid gap-3 sm:grid-cols-2">
      <Field label="Search" className="sm:col-span-2">
        <Input type="search" name="q" defaultValue={search.q} placeholder="Title or description" />
      </Field>
      <Field label="City">
        <Input name="city" defaultValue={search.city} />
      </Field>
      <Field label="Category">
        <select name="category" defaultValue={search.category ?? ''} className={selectClass}>
          <option value="">Any category</option>
          <Options labels={eventCategoryLabels} />
        </select>
      </Field>
      <Field label="From">
        <Input type="date" name="from" defaultValue={search.from} />
      </Field>
      <Field label="To">
        <Input type="date" name="to" defaultValue={search.to} />
      </Field>
      <Button type="submit" className="sm:col-span-2 sm:justify-self-start">Search</Button>
    </form>
  )
}

function Results({ search, results }: { search: EventSearch; results: ReturnType<typeof useEventSearch> }) {
  if (results.isPending) return <p className="font-mono text-muted-foreground">Looking for Events…</p>
  if (results.isError) {
    return (
      <div className="flex flex-col items-start gap-4">
        <p role="alert" className="text-destructive">We couldn&apos;t load Events. {problemMessage(results.error)}</p>
        <Button variant="outline" onClick={() => void results.refetch()}>Try again</Button>
      </div>
    )
  }

  const events = results.data.content ?? []
  const totalPages = results.data.page?.totalPages ?? 0
  // The page these results are for, which lags the URL while the next page loads.
  const shown = { ...search, page: (results.data.page?.number ?? search.page - 1) + 1 }
  if (!events.length && totalPages > 0) {
    return (
      <MessageCard title="No Events on this page" description={`There are only ${totalPages} pages of results.`}>
        <Link to={eventsHref({ ...search, page: 1 })} className="underline">Go to the first page</Link>
      </MessageCard>
    )
  }
  if (!events.length) {
    return (
      <MessageCard title="No Events match" description="Try fewer filters, or a different search.">
        <Link to="/events" className="underline">Clear the filters</Link>
      </MessageCard>
    )
  }

  return (
    <>
      <ul aria-label="Events" aria-busy={results.isPlaceholderData} className={cn('grid gap-4', results.isPlaceholderData && 'opacity-60')}>
        {events.map((event) => <ResultCard key={event.id} event={event} />)}
      </ul>
      {totalPages > 1 && (
        <nav aria-label="Pages" className="flex items-center justify-between gap-4 font-mono text-sm">
          {shown.page > 1 ? <Link to={eventsHref({ ...shown, page: shown.page - 1 })} className="underline">Previous</Link> : <span />}
          <span>Page {shown.page} of {totalPages}</span>
          {shown.page < totalPages ? <Link to={eventsHref({ ...shown, page: shown.page + 1 })} className="underline">Next</Link> : <span />}
        </nav>
      )}
    </>
  )
}

function ResultCard({ event }: { event: EventCard }) {
  const { nextShow, lowestPrice } = event
  return (
    <li>
      <Card className="flex-row gap-4 px-6">
        {event.category && (
          <img src={categoryArt[event.category]} alt="" className="w-20 shrink-0 self-start rounded-sm object-cover aspect-[2/3]" />
        )}
        <CardHeader className="flex-1 px-0">
          <CardTitle>
            <h2 className="font-display text-2xl font-black uppercase leading-none">
              <Link to={`/events/${event.id}`} className="hover:underline">{event.title}</Link>
            </h2>
          </CardTitle>
          <CardDescription className="flex flex-col gap-1">
            {event.category && <span className="font-mono text-foreground">{eventCategoryLabels[event.category]}</span>}
            {nextShow?.startsAt && nextShow.timeZone ? (
              <span className="font-mono text-foreground">
                <time dateTime={nextShow.startsAt}>{formatShowTime(nextShow.startsAt, nextShow.timeZone)}</time>
                <br />
                {nextShow.venueName}, {nextShow.city}
              </span>
            ) : (
              <span className="font-mono">No upcoming Shows</span>
            )}
            {nextShow && lowestPrice?.amountPaise !== undefined && (
              <span className="font-mono font-bold text-foreground">from {formatPrice(lowestPrice.amountPaise)}</span>
            )}
            <span className="line-clamp-2">{event.description}</span>
          </CardDescription>
        </CardHeader>
      </Card>
    </li>
  )
}

export default EventsPage
