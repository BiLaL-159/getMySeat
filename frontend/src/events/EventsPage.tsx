import type { FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useEventSearch, type EventResponse } from '@/api/events.ts'
import MessageCard from '@/app/MessageCard.tsx'
import { problemMessage } from '@/app/problemMessage.ts'
import { Button } from '@/components/ui/button.tsx'
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card.tsx'
import { Input } from '@/components/ui/input.tsx'
import {
  categoryLabels,
  readSearch,
  sortLabels,
  toApiQuery,
  writeSearch,
  type Category,
  type EventSearch,
  type Sort,
} from './search.ts'

const selectClass =
  'h-9 w-full rounded-md border border-input bg-transparent px-3 text-base shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 md:text-sm dark:bg-input/30'

function href(search: EventSearch) {
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
          {Object.entries(sortLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <Results search={search} results={results} />
    </div>
  )
}

function SearchForm({ search, onSubmit }: { search: EventSearch; onSubmit: (event: FormEvent<HTMLFormElement>) => void }) {
  return (
    <form role="search" onSubmit={onSubmit} className="grid gap-3 sm:grid-cols-2">
      <label className="flex flex-col gap-1 font-mono text-sm sm:col-span-2">
        Search
        <Input type="search" name="q" defaultValue={search.q} placeholder="Title or description" />
      </label>
      <label className="flex flex-col gap-1 font-mono text-sm">
        City
        <Input name="city" defaultValue={search.city} />
      </label>
      <label className="flex flex-col gap-1 font-mono text-sm">
        Category
        <select name="category" defaultValue={search.category ?? ''} className={selectClass}>
          <option value="">Any category</option>
          {Object.entries(categoryLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <label className="flex flex-col gap-1 font-mono text-sm">
        From
        <Input type="date" name="from" defaultValue={search.from} />
      </label>
      <label className="flex flex-col gap-1 font-mono text-sm">
        To
        <Input type="date" name="to" defaultValue={search.to} />
      </label>
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
  if (!events.length && totalPages > 0) {
    return (
      <MessageCard title="No Events on this page" description={`There are only ${totalPages} pages of results.`}>
        <Link to={href({ ...search, page: 1 })} className="underline">Go to the first page</Link>
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
      <ul aria-label="Events" className="grid gap-4">
        {events.map((event) => <EventCard key={event.id} event={event} />)}
      </ul>
      {totalPages > 1 && (
        <nav aria-label="Pages" className="flex items-center justify-between gap-4 font-mono text-sm">
          {search.page > 1 ? <Link to={href({ ...search, page: search.page - 1 })} className="underline">Previous</Link> : <span />}
          <span>Page {search.page} of {totalPages}</span>
          {search.page < totalPages ? <Link to={href({ ...search, page: search.page + 1 })} className="underline">Next</Link> : <span />}
        </nav>
      )}
    </>
  )
}

function EventCard({ event }: { event: EventResponse }) {
  return (
    <li>
      <Card className="gap-2">
        <CardHeader>
          <CardTitle>
            <h2 className="font-display text-2xl font-black uppercase leading-none">
              <Link to={`/events/${event.id}`} className="hover:underline">{event.title}</Link>
            </h2>
          </CardTitle>
          <CardDescription className="flex flex-col gap-1">
            {event.category && <span className="font-mono text-foreground">{categoryLabels[event.category as Category]}</span>}
            <span className="line-clamp-2">{event.description}</span>
          </CardDescription>
        </CardHeader>
      </Card>
    </li>
  )
}

export default EventsPage
