import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { useAuth } from 'react-oidc-context'
import { api } from './api.ts'
import { localExpiry } from './clock.ts'
import { ApiError, shouldRetryHold } from './problem.ts'
import { showAvailabilityKey } from './shows.ts'
import type { components } from './schema'

export type HoldRequest = components['schemas']['HoldRequest']
// A Hold as the API answered it, with `endsAt`: when it expires by this device's clock.
export type Hold = components['schemas']['HoldResponse'] & { endsAt: number }

const myHoldKey = (showId: string, subject?: string) => ['my-hold', showId, ...(subject ? [subject] : [])]

// The API's answer, timed by its Date header. `expiresAt` is always there on a Hold.
function withLocalExpiry(hold: components['schemas']['HoldResponse'], response: Response): Hold {
  return { ...hold, endsAt: localExpiry(hold.expiresAt!, response.headers.get('Date'), Date.now()) }
}

// The signed-in Customer's active Hold for a Show, or null if they have none.
async function fetchMyHold(showId: string) {
  try {
    // A failure rejects with an ApiError, so data is there.
    const { data, response } = await api.GET('/api/v1/shows/{id}/holds/mine', { params: { path: { id: showId } } })
    return withLocalExpiry(data!, response)
  } catch (error) {
    if (error instanceof ApiError && error.problem.kind === 'not-found') return null
    throw error
  }
}

// The signed-in Customer's active Hold for a Show, say after a reload, or null if they have none.
export function useMyHold(showId: string, { enabled }: { enabled: boolean }) {
  const auth = useAuth()
  return useQuery({
    queryKey: myHoldKey(showId, auth.user?.profile.sub),
    enabled: enabled && auth.isAuthenticated,
    queryFn: () => fetchMyHold(showId),
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
      const { data, response } = await api.POST('/api/v1/shows/{id}/holds', {
        params: { path: { id: showId }, header: { 'Idempotency-Key': key } },
        body: request,
      })
      return withLocalExpiry(data!, response)
    },
    retry: shouldRetryHold,
    retryDelay: (failures) => 250 * 2 ** failures,
    onSuccess: (hold) => queryClient.setQueryData(myHoldKey(showId, auth.user?.profile.sub), hold),
    onSettled: () => queryClient.invalidateQueries({ queryKey: showAvailabilityKey(showId) }),
  })
}

// How releasing a Hold went: released, or it had already ended (expired, or replaced) first.
export type Release = 'released' | 'already-ended'

// Releases a Hold early. The API answers both "not active any more" and "other Holds got in the way,
// try again" with a 409, so the Customer's Hold for the Show settles which: this Hold still active
// means the release just didn't happen; anything else is what they hold now, if anything.
export function useReleaseHold(showId: string) {
  const auth = useAuth()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (holdId: string): Promise<Release> => {
      const key = myHoldKey(showId, auth.user?.profile.sub)
      try {
        await api.POST('/api/v1/holds/{id}/release', { params: { path: { id: holdId } } })
        queryClient.setQueryData(key, null)
        return 'released'
      } catch (error) {
        if (!(error instanceof ApiError && error.problem.kind === 'conflict')) throw error
        const current = await fetchMyHold(showId)
        if (current?.id === holdId) throw error
        queryClient.setQueryData(key, current)
        return 'already-ended'
      }
    },
    // Not waited for, so the Hold shows how it ended straight away.
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: showAvailabilityKey(showId) }),
  })
}

// Once a Hold's time is up: asking for it again expires it on the API, which gives its inventory
// back, and then availability is asked for.
export function useHoldExpired(showId: string) {
  const queryClient = useQueryClient()
  return useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: myHoldKey(showId) })
    await queryClient.invalidateQueries({ queryKey: showAvailabilityKey(showId) })
  }, [queryClient, showId])
}
