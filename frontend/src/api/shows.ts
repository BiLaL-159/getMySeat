import { useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { api } from './api.ts'
import type { components } from './schema'

export type ShowDetail = components['schemas']['ShowDetail']
export type SectionDetail = components['schemas']['SectionDetail']
export type ShowAvailability = components['schemas']['ShowAvailability']

// How often a Show page asks what's left, until live updates arrive.
const availabilityRefreshMs = 15_000

export const sectionKindLabels: Record<NonNullable<SectionDetail['kind']>, string> = {
  SEATED: 'Seated',
  GENERAL_ADMISSION: 'General Admission',
}

// A published Show with its Venue and Sections, for anyone. Keyed by the caller too, since an
// Event's owner also sees their drafts.
export function useShow(id: string) {
  const auth = useAuth()
  return useQuery({
    queryKey: ['show', id, auth.user?.profile.sub],
    queryFn: async () => {
      // A failure rejects with an ApiError, so data is there.
      const { data } = await api.GET('/api/v1/shows/{id}', { params: { path: { id } } })
      return data!
    },
  })
}

export const showAvailabilityKey = (id: string) => ['show-availability', id]

// What's left to sell at a published Show, the same for everyone. While `live`, it's asked again
// every so often; the last answer stays up meanwhile.
export function useShowAvailability(id: string, { enabled, live }: { enabled: boolean; live: boolean }) {
  return useQuery({
    queryKey: showAvailabilityKey(id),
    enabled,
    refetchInterval: live ? availabilityRefreshMs : false,
    queryFn: async () => {
      // A failure rejects with an ApiError, so data is there.
      const { data } = await api.GET('/api/v1/shows/{id}/availability', { params: { path: { id } } })
      return data!
    },
  })
}
