import type { UseQueryResult } from '@tanstack/react-query'
import { Link } from 'react-router'
import { useEventSearch } from '@/api/events.ts'
import { formatShowPart } from '@/shows/format.ts'
import { isOnTonight, isScheduled, tonightQuery } from './whatsOn.ts'

// The strip under the hero: what's on today in the city, at each Venue's own today.
export function Tonight({ city, cities }: { city: string | undefined; cities: UseQueryResult<string[]> }) {
  const now = new Date()
  const results = useEventSearch(city ? tonightQuery(city, now) : undefined)
  // Another city's line-up, kept while this one loads, would sit under the wrong name.
  const loaded = results.isSuccess && !results.isPlaceholderData
  const tonight = loaded ? (results.data.content?.filter(isScheduled).filter((event) => isOnTonight(event, now)) ?? []) : []

  let items
  if (cities.isError || results.isError) {
    items = <li><span>We couldn&apos;t load tonight&apos;s line-up.</span></li>
  } else if (cities.isSuccess && (!city || loaded) && !tonight.length) {
    items = <li><span>Nothing on tonight.</span></li>
  } else {
    items = tonight.map(({ id, title, nextShow }) => (
      <li key={id}>
        <Link to={`/events/${id}`}>
          <b>{title}</b> <span><time dateTime={nextShow.startsAt}>{formatShowPart(nextShow.startsAt, nextShow.timeZone, { hour: 'numeric', minute: '2-digit' })}</time> · {nextShow.venueName}</span>
        </Link>
      </li>
    ))
  }

  return (
    <div className="tonight">
      <div className="in">
        <span className="label" id="tonightLabel">{city ? `Tonight in ${city}` : 'Tonight'}</span>
        <ul id="tonightList" aria-labelledby="tonightLabel">{items}</ul>
      </div>
    </div>
  )
}
