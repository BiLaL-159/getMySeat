import { describe, expect, it } from 'vitest'
import { defaultSearch, readSearch, toApiQuery, writeSearch } from './search.ts'

const read = (query: string) => readSearch(new URLSearchParams(query))

describe('readSearch', () => {
  it('reads every filter from the URL', () => {
    expect(read('q=indie&city=Mumbai&category=MUSIC&from=2026-10-01&to=2026-10-31&sort=title&page=3')).toEqual({
      q: 'indie',
      city: 'Mumbai',
      category: 'MUSIC',
      from: '2026-10-01',
      to: '2026-10-31',
      sort: 'title',
      page: 3,
    })
  })

  it('defaults to the first page, newest first, with no filters', () => {
    expect(read('')).toEqual(defaultSearch)
    expect(defaultSearch).toEqual({ sort: 'publishedAt', page: 1 })
  })

  it('trims text filters and drops blank ones', () => {
    expect(read('q=%20%20indie%20&city=%20')).toEqual({ ...defaultSearch, q: 'indie' })
  })

  it.each([
    ['an unknown category', 'category=JAZZ'],
    ['a lower-case category', 'category=music'],
    ['a date that is not ISO', 'from=01/10/2026'],
    ['a date that does not exist', 'to=2026-02-30'],
    ['an unknown sort', 'sort=price'],
    ['a page of zero', 'page=0'],
    ['a negative page', 'page=-2'],
    ['a fractional page', 'page=1.5'],
    ['a page that is not a number', 'page=two'],
    ['a page past what the API can read', 'page=1e10'],
  ])('ignores %s', (_, query) => {
    expect(read(query)).toEqual(defaultSearch)
  })

  it('ignores an end date before the start date, keeping the start', () => {
    expect(read('from=2026-10-10&to=2026-10-01')).toEqual({ ...defaultSearch, from: '2026-10-10' })
  })

  it('keeps a single-day range', () => {
    expect(read('from=2026-10-10&to=2026-10-10')).toMatchObject({ from: '2026-10-10', to: '2026-10-10' })
  })
})

describe('writeSearch', () => {
  it('writes every filter, leaving out the defaults', () => {
    expect(writeSearch({ ...defaultSearch, q: 'indie', category: 'COMEDY' }).toString()).toBe('q=indie&category=COMEDY')
    expect(writeSearch(defaultSearch).toString()).toBe('')
  })

  it('writes a sort and page other than the default', () => {
    expect(writeSearch({ ...defaultSearch, sort: 'title', page: 2 }).toString()).toBe('sort=title&page=2')
  })

  it('round-trips through readSearch', () => {
    const search = { q: 'stand up', city: 'Pune', category: 'COMEDY', from: '2026-10-01', to: '2026-10-31', sort: 'title', page: 4 } as const
    expect(readSearch(writeSearch(search))).toEqual(search)
  })
})

describe('toApiQuery', () => {
  it('sends the filters, the zero-based page and the sort direction', () => {
    expect(toApiQuery({ q: 'indie', city: 'Mumbai', category: 'MUSIC', from: '2026-10-01', to: '2026-10-31', sort: 'title', page: 3 })).toEqual({
      q: 'indie',
      city: 'Mumbai',
      category: 'MUSIC',
      from: '2026-10-01',
      to: '2026-10-31',
      sort: ['title,asc'],
      page: 2,
      size: 12,
    })
  })

  it('asks for the most recently published first by default, sending no empty filters', () => {
    expect(toApiQuery(defaultSearch)).toEqual({ sort: ['publishedAt,desc'], page: 0, size: 12 })
  })

  it('asks for the soonest next Show first', () => {
    expect(toApiQuery({ ...defaultSearch, sort: 'nextShow' })).toEqual({ sort: ['nextShow,asc'], page: 0, size: 12 })
  })
})
