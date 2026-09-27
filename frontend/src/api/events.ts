import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { api } from './api.ts'
import type { components, operations } from './schema'

export type EventResponse = components['schemas']['EventResponse']
// A search result: the Event with where and when it's next on, and its lowest price.
export type EventCard = components['schemas']['EventCard']
export type EventSearchQuery = NonNullable<operations['search_1']['parameters']['query']>
export type EventCategory = NonNullable<EventResponse['category']>

export const eventCategoryLabels: Record<EventCategory, string> = {
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

// A published Event, for anyone. Keyed by the caller too, since its owner also sees drafts.
export function useEvent(id: string | undefined) {
  const auth = useAuth()
  return useQuery({
    queryKey: ['event', id, auth.user?.profile.sub],
    enabled: !!id,
    queryFn: async () => {
      // A failure rejects with an ApiError, so data is there.
      const { data } = await api.GET('/api/v1/events/{id}', { params: { path: { id: id! } } })
      return data!
    },
  })
}

// A page of published Events, the same for everyone. The last page stays up while the next loads.
export function useEventSearch(query: EventSearchQuery) {
  return useQuery({
    queryKey: ['events', query],
    placeholderData: keepPreviousData,
    queryFn: async () => {
      // A failure rejects with an ApiError, so data is there.
      const { data } = await api.GET('/api/v1/events', { params: { query } })
      return data!
    },
  })
}

const showsPageSize = 20

// One page (zero-based) of an Event's upcoming Shows, soonest first. Keyed by the caller too,
// since its owner also sees drafts and past Shows. The last page stays up while the next loads.
export function useEventShows(eventId: string, page: number) {
  const auth = useAuth()
  return useQuery({
    queryKey: ['event-shows', eventId, page, auth.user?.profile.sub],
    placeholderData: keepPreviousData,
    queryFn: async () => {
      // A failure rejects with an ApiError, so data is there.
      const { data } = await api.GET('/api/v1/events/{eventId}/shows', {
        params: { path: { eventId }, query: { page, size: showsPageSize } },
      })
      return data!
    },
  })
}
