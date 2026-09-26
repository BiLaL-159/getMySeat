import { describe, expect, it, vi } from 'vitest'
import { createApiClient } from './client.ts'
import { ApiError } from './problem.ts'

function recordingFetch() {
  const requests: Request[] = []
  const fetch = vi.fn(async (request: Request) => {
    requests.push(request)
    return Response.json({ name: 'Asha', roles: ['CUSTOMER'] })
  })
  return { fetch, requests }
}

describe('createApiClient', () => {
  it('attaches the current access token as a bearer token', async () => {
    const { fetch, requests } = recordingFetch()
    const api = createApiClient({ baseUrl: 'http://api.test', getAccessToken: () => 'token-123', fetch })

    await api.GET('/api/v1/me')

    expect(requests[0].headers.get('Authorization')).toBe('Bearer token-123')
  })

  it('reads the token on every request, so a renewed token is used', async () => {
    const { fetch, requests } = recordingFetch()
    let token = 'first'
    const api = createApiClient({ baseUrl: 'http://api.test', getAccessToken: async () => token, fetch })

    await api.GET('/api/v1/me')
    token = 'renewed'
    await api.GET('/api/v1/me')

    expect(requests.map((r) => r.headers.get('Authorization'))).toEqual(['Bearer first', 'Bearer renewed'])
  })

  it('sends no Authorization header when signed out', async () => {
    const { fetch, requests } = recordingFetch()
    const api = createApiClient({ baseUrl: 'http://api.test', getAccessToken: () => undefined, fetch })

    await api.GET('/api/v1/me')

    expect(requests[0].headers.has('Authorization')).toBe(false)
  })

  it('calls the API at the configured base URL', async () => {
    const { fetch, requests } = recordingFetch()
    const api = createApiClient({ baseUrl: 'http://api.test', getAccessToken: () => 'token', fetch })

    const { data } = await api.GET('/api/v1/me')

    expect(requests[0].url).toBe('http://api.test/api/v1/me')
    expect(data).toEqual({ name: 'Asha', roles: ['CUSTOMER'] })
  })

  it('turns an error response into an ApiError by its ProblemDetail type', async () => {
    const fetch = async () =>
      Response.json({ type: 'urn:getmyseat:problem:forbidden', status: 403, detail: 'Admins only.' }, { status: 403 })
    const api = createApiClient({ baseUrl: 'http://api.test', getAccessToken: () => 'token', fetch })

    const error = await api.GET('/api/v1/me').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).problem).toEqual({ kind: 'forbidden', status: 403, detail: 'Admins only.' })
  })

  it('reports the API as unreachable when the request never gets a response', async () => {
    const fetch = async () => {
      throw new TypeError('Failed to fetch')
    }
    const api = createApiClient({ baseUrl: 'http://api.test', getAccessToken: () => 'token', fetch })

    const error = await api.GET('/api/v1/me').catch((e: unknown) => e)

    expect((error as ApiError).problem).toEqual({ kind: 'unreachable' })
  })

  it('re-authenticates on a 401 and retries once with the renewed token', async () => {
    const requests: Request[] = []
    const fetch = async (request: Request) => {
      requests.push(request)
      return requests.length === 1
        ? Response.json({ type: 'urn:getmyseat:problem:unauthorized', status: 401 }, { status: 401 })
        : Response.json({ id: 'application-1' })
    }
    const reauthenticate = vi.fn(async () => 'renewed')
    const api = createApiClient({ baseUrl: 'http://api.test', getAccessToken: () => 'expired', fetch, reauthenticate })

    const { data } = await api.POST('/api/v1/organizer-applications', {
      body: { organisationName: 'Olive Live', contactPhone: '+91 98765 43210', description: 'Gigs' },
    })

    expect(data).toEqual({ id: 'application-1' })
    expect(reauthenticate).toHaveBeenCalledOnce()
    expect(requests.map((r) => r.headers.get('Authorization'))).toEqual(['Bearer expired', 'Bearer renewed'])
    expect(await requests[1].json()).toMatchObject({ organisationName: 'Olive Live' })
  })

  it('gives up with an unauthorized ApiError when re-authentication fails', async () => {
    const fetch = vi.fn(async () => Response.json({ type: 'urn:getmyseat:problem:unauthorized', status: 401 }, { status: 401 }))
    const api = createApiClient({
      baseUrl: 'http://api.test',
      getAccessToken: () => 'expired',
      fetch,
      reauthenticate: async () => undefined,
    })

    const error = await api.GET('/api/v1/me').catch((e: unknown) => e)

    expect((error as ApiError).problem).toMatchObject({ kind: 'unauthorized', status: 401 })
    expect(fetch).toHaveBeenCalledOnce()
  })

  it('does not re-authenticate a signed-out call', async () => {
    const fetch = async () => Response.json({ type: 'urn:getmyseat:problem:unauthorized', status: 401 }, { status: 401 })
    const reauthenticate = vi.fn(async () => 'token')
    const api = createApiClient({ baseUrl: 'http://api.test', getAccessToken: () => undefined, fetch, reauthenticate })

    await api.GET('/api/v1/me').catch(() => {})

    expect(reauthenticate).not.toHaveBeenCalled()
  })

  it('ends the session when even the renewed token is rejected', async () => {
    const fetch = vi.fn(async () => Response.json({ type: 'urn:getmyseat:problem:unauthorized', status: 401 }, { status: 401 }))
    const expireSession = vi.fn()
    const api = createApiClient({
      baseUrl: 'http://api.test',
      getAccessToken: () => 'expired',
      fetch,
      reauthenticate: async () => 'renewed',
      expireSession,
    })

    const error = await api.GET('/api/v1/me').catch((e: unknown) => e)

    expect((error as ApiError).problem).toMatchObject({ kind: 'unauthorized' })
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(expireSession).toHaveBeenCalledOnce()
  })
})
