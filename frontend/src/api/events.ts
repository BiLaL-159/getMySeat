import { useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { api } from './api.ts'

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
