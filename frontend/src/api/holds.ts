import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { shouldRetryHold, type HoldRequest } from '@/shows/holding.ts'
import { api } from './api.ts'
import { ApiError } from './problem.ts'
import type { components } from './schema'

export type Hold = components['schemas']['HoldResponse']
export type HoldItem = components['schemas']['HoldItemResponse']

const myHoldKey = (showId: string, subject: string | undefined) => ['my-hold', showId, subject]

// The signed-in Customer's active Hold for a Show, say after a reload, or null if they have none.
export function useMyHold(showId: string, { enabled }: { enabled: boolean }) {
  const auth = useAuth()
  return useQuery({
    queryKey: myHoldKey(showId, auth.user?.profile.sub),
    enabled: enabled && auth.isAuthenticated,
    queryFn: async () => {
      try {
        // A failure rejects with an ApiError, so data is there.
        const { data } = await api.GET('/api/v1/shows/{id}/holds/mine', { params: { path: { id: showId } } })
        return data!
      } catch (error) {
        if (error instanceof ApiError && error.problem.kind === 'not-found') return null
        throw error
      }
    },
  })
}

// Holds tickets at a Show under the attempt's Idempotency-Key. A retry sends the same key, so it
// never holds twice. Either way, what's left has changed, so availability is asked for again.
export function useCreateHold(showId: string) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ request, key }: { request: HoldRequest; key: string }) => {
      // A failure rejects with an ApiError, so data is there.
      const { data } = await api.POST('/api/v1/shows/{id}/holds', {
        params: { path: { id: showId }, header: { 'Idempotency-Key': key } },
        body: request,
      })
      return data!
    },
    retry: shouldRetryHold,
    retryDelay: (failures) => 250 * 2 ** failures,
    onSuccess: (hold) => queryClient.setQueryData(myHoldKey(showId, auth.user?.profile.sub), hold),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['show-availability', showId] }),
  })
}
