import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { api } from './api.ts'
import type { components, operations } from './schema'

export type EventResponse = components['schemas']['EventResponse']
export type EventSearchQuery = NonNullable<operations['search_1']['parameters']['query']>

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
      const { data } = await api.GET('/api/v1/events', { params: { query } })
      return data!
    },
  })
}
