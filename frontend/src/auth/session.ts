import { ErrorResponse } from 'oidc-client-ts'
import { useCallback, useSyncExternalStore } from 'react'
import { useAuth } from 'react-oidc-context'
import { unreachable } from '@/api/problem.ts'
import { userManager } from './userManager.ts'

// Tokens live in memory only, so every page load starts signed out. restoreSession asks Keycloak,
// in a hidden iframe (prompt=none), whether the browser still has a session, and loads the user
// if so. Until it settles the session is 'signingIn', so nothing flashes "Sign in" meanwhile.
// When the API rejects the token, renewSession tries the same; if Keycloak's session has ended too,
// the session is 'expired' until the visitor signs in again.
type SessionState = { restoring: boolean; expired: boolean }
const initialState: SessionState = { restoring: false, expired: false }
let current = initialState
let renewing: Promise<string | undefined> | undefined
const listeners = new Set<() => void>()

function update(changes: Partial<SessionState>) {
  current = { ...current, ...changes }
  listeners.forEach((listener) => listener())
}

export async function restoreSession() {
  update({ restoring: true })
  try {
    await userManager.signinSilent()
    update({ expired: false })
  } catch {
    // login_required (no Keycloak session) or Keycloak unreachable: stay signed out.
  } finally {
    update({ restoring: false })
  }
}

// For the API client's 401 handling: a fresh access token, or undefined once the session is over.
// Only Keycloak saying no (login_required) ends the session; if it can't be reached, the call fails
// as unreachable and the session is left alone. Calls that fail together share one renewal.
export function renewSession() {
  if (current.expired) return Promise.resolve(undefined)
  renewing ??= (async () => {
    try {
      const user = await userManager.signinSilent()
      update({ expired: false })
      return user?.access_token
    } catch (error) {
      if (!(error instanceof ErrorResponse)) throw unreachable(error)
      await expireSession()
      return undefined
    } finally {
      renewing = undefined
    }
  })()
  return renewing
}

export async function expireSession() {
  update({ expired: true })
  await userManager.removeUser()
}

// For tests: forget any restore or expiry from a previous test.
export function resetSession() {
  renewing = undefined
  update(initialState)
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export type SessionStatus = 'signedOut' | 'signingIn' | 'signedIn' | 'expired'

export function useSession() {
  const auth = useAuth()
  const { restoring, expired } = useSyncExternalStore(subscribe, () => current)
  const { signinRedirect, signoutRedirect } = auth
  const signIn = useCallback((returnTo: string) => void signinRedirect({ state: { returnTo } }), [signinRedirect])
  const signOut = useCallback(() => void signoutRedirect(), [signoutRedirect])

  // Expired wins: the page may still hold the old user until removeUser settles.
  const status: SessionStatus = expired
    ? 'expired'
    : auth.isAuthenticated
      ? 'signedIn'
      : restoring || auth.isLoading || auth.activeNavigator
        ? 'signingIn'
        : 'signedOut'

  return {
    status,
    // For display only; roles come from GET /api/v1/me.
    firstName: auth.user?.profile.given_name ?? auth.user?.profile.name,
    error: auth.error,
    // Where the callback should land once Keycloak sends the visitor back.
    returnTo: returnToOf(auth.user?.state),
    signIn,
    signOut,
  }
}

// Only same-site paths, so a crafted state can't send the visitor elsewhere.
function returnToOf(state: unknown) {
  const returnTo = (state as { returnTo?: unknown } | undefined)?.returnTo
  return typeof returnTo === 'string' && returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : '/app'
}
