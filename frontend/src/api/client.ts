import createClient from 'openapi-fetch'
import { ApiError, problemFrom, unreachable } from './problem.ts'
import type { paths } from './schema'

type ApiClientOptions = {
  baseUrl: string
  // Read on every request, so a silently renewed token is picked up.
  getAccessToken: () => string | undefined | Promise<string | undefined>
  fetch?: (request: Request) => Promise<Response>
  // Called when the API rejects the token (401): returns a fresh token, or undefined if the session is over.
  reauthenticate?: () => Promise<string | undefined>
  // Called when the API rejects even the fresh token.
  expireSession?: () => void | Promise<void>
}

// A failed call rejects with an ApiError (see problem.ts), so a resolved call always has `data`.
// A 401 on a signed-in call re-authenticates and retries the call once.
export function createApiClient({ baseUrl, getAccessToken, fetch = (request) => globalThis.fetch(request), reauthenticate, expireSession }: ApiClientOptions) {
  const client = createClient<paths>({ baseUrl, fetch })
  // Sending a request uses up its body, so keep a copy of each signed-in request for the retry.
  const retryable = new Map<string, Request>()
  client.use({
    async onRequest({ request, id }) {
      const token = await getAccessToken()
      if (token) {
        request.headers.set('Authorization', `Bearer ${token}`)
        if (reauthenticate) retryable.set(id, request.clone())
      }
      return request
    },
    async onResponse({ response, id }) {
      const original = retryable.get(id)
      retryable.delete(id)
      if (response.status === 401 && original && reauthenticate) {
        const token = await reauthenticate()
        if (token) {
          original.headers.set('Authorization', `Bearer ${token}`)
          response = await fetch(original).catch((error: unknown) => {
            throw unreachable(error)
          })
          if (response.status === 401) await expireSession?.()
        }
      }
      if (response.ok) return response
      const body = await response.json().catch(() => undefined)
      throw new ApiError(problemFrom(response.status, body))
    },
    onError({ error, id }) {
      retryable.delete(id)
      return unreachable(error)
    },
  })
  return client
}
