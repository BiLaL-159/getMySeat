import type { EventSearchQuery } from '@/api/events.ts'

export type Category = NonNullable<EventSearchQuery['category']>
export type Sort = 'publishedAt' | 'title'

// A search for published Events, as it lives in the /events URL. `page` counts from 1.
export type EventSearch = {
  q?: string
  city?: string
  category?: Category
  from?: string
  to?: string
  sort: Sort
  page: number
}

export const defaultSearch: EventSearch = { sort: 'publishedAt', page: 1 }

export const pageSize = 12

export const categoryLabels: Record<Category, string> = {
  MUSIC: 'Music',
  COMEDY: 'Comedy',
  THEATRE: 'Theatre',
  DANCE: 'Dance',
  SPORTS: 'Sports',
  CONFERENCE: 'Conference',
  WORKSHOP: 'Workshop',
  FAMILY: 'Family',
  OTHER: 'Other',
}

export const sortLabels: Record<Sort, string> = {
  publishedAt: 'Newest first',
  title: 'Title, A to Z',
}

const sortDirections: Record<Sort, string> = { publishedAt: 'publishedAt,desc', title: 'title,asc' }

function isCategory(value: string): value is Category {
  return Object.hasOwn(categoryLabels, value)
}

function isSort(value: string): value is Sort {
  return Object.hasOwn(sortLabels, value)
}

// A real calendar date written as YYYY-MM-DD, the only form the API takes.
function isIsoDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().startsWith(value)
}

function text(value: string | null) {
  return value?.trim() || undefined
}

// Anything in the URL the API would reject is left out, so a mistyped or stale link still shows
// results rather than an error.
export function readSearch(params: URLSearchParams): EventSearch {
  const search: EventSearch = { ...defaultSearch }
  const q = text(params.get('q'))
  const city = text(params.get('city'))
  const category = params.get('category')
  const from = params.get('from')
  const to = params.get('to')
  const sort = params.get('sort')
  const page = Number(params.get('page'))

  if (q) search.q = q
  if (city) search.city = city
  if (category && isCategory(category)) search.category = category
  if (from && isIsoDate(from)) search.from = from
  // ISO dates compare as strings. The API refuses an end before the start.
  if (to && isIsoDate(to) && !(search.from && to < search.from)) search.to = to
  if (sort && isSort(sort)) search.sort = sort
  if (Number.isInteger(page) && page >= 1) search.page = page
  return search
}

export function writeSearch(search: EventSearch): URLSearchParams {
  const params = new URLSearchParams()
  for (const key of ['q', 'city', 'category', 'from', 'to'] as const) {
    const value = search[key]
    if (value) params.set(key, value)
  }
  if (search.sort !== defaultSearch.sort) params.set('sort', search.sort)
  if (search.page !== defaultSearch.page) params.set('page', String(search.page))
  return params
}

export function toApiQuery({ sort, page, ...filters }: EventSearch): EventSearchQuery {
  return { ...filters, sort: [sortDirections[sort]], page: page - 1, size: pageSize }
}
