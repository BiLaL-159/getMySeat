import { useState } from 'react'
import { Link } from 'react-router'
import { eventCategoryLabels, useEventSearch } from '@/api/events.ts'
import { categoryArt } from '@/events/categoryArt.ts'
import { formatPrice, formatShowPart } from '@/shows/format.ts'
import { guideQuery, isScheduled, type GuideTab, type ScheduledEvent } from './whatsOn.ts'

const tabs: [GuideTab, string][] = [['ALL', 'All'], ['MUSIC', 'Music'], ['COMEDY', 'Comedy'], ['THEATRE', 'Theatre'], ['SPORTS', 'Sport']]

// "What's on": upcoming Events, soonest first, filtered by category.
export function GigGuide() {
  const [tab, setTab] = useState<GuideTab>('ALL')
  const results = useEventSearch(guideQuery(tab, new Date()))
  const events = (results.data?.content ?? []).filter(isScheduled)

  let gigs
  if (results.isError) gigs = <p className="empty">We couldn&apos;t load what&apos;s on. Try again in a little while.</p>
  else if (results.isPending) gigs = null
  else if (!events.length) gigs = <p className="empty">{tab === 'ALL' ? 'Nothing coming up yet. Check back soon.' : 'Nothing here yet. Try another type.'}</p>
  else gigs = events.map((event) => <Gig key={event.id} event={event} />)

  return (
    <section className="guide" id="guide" aria-labelledby="guideTitle">
      <div className="in">
        <div className="guide-head">
          <h2 id="guideTitle">What&apos;s on</h2>
          <div className="tabs label" role="group" aria-label="Filter by type">
            {tabs.map(([value, label]) => (
              <button key={value} aria-pressed={tab === value} onClick={() => setTab(value)}>{label}</button>
            ))}
          </div>
        </div>
        <div id="gigs" aria-busy={results.isPlaceholderData}>{gigs}</div>
      </div>
    </section>
  )
}

function Gig({ event: { id, title, category, nextShow, lowestPrice } }: { event: ScheduledEvent }) {
  const art = categoryArt[category ?? 'OTHER']
  const at = (part: Intl.DateTimeFormatOptions) => formatShowPart(nextShow.startsAt, nextShow.timeZone, part)
  return (
    // data-poster is the artwork mountLanding floats beside the pointer.
    <Link className="gig" to={`/events/${id}`} data-poster={art}>
      <time className="date" dateTime={nextShow.startsAt}><b className="num">{at({ day: 'numeric' })}</b><span className="label">{at({ month: 'short' })}<br />{at({ weekday: 'short' })}</span></time>
      <div className="t">
        <img className="thumb" src={art} alt="" loading="lazy" />
        <div>
          <h3>{title}</h3>
          <div className="sub">
            {nextShow.venueName}, {nextShow.city}{category && ` · ${eventCategoryLabels[category]}`} · {at({ hour: 'numeric', minute: '2-digit' })}
          </div>
        </div>
      </div>
      {/* Browse results don't say how many seats are left; the empty cell keeps the columns. */}
      <div className="avail" />
      <div className="from num">{lowestPrice?.amountPaise !== undefined && <><small>From</small>{formatPrice(lowestPrice.amountPaise)}</>}</div>
      <span className="go">Seats <i aria-hidden="true">→</i></span>
    </Link>
  )
}
