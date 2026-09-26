import type { AuthContextProps } from 'react-oidc-context'
import { vi } from 'vitest'

// Stands in for react-oidc-context in tests: `vi.mock('react-oidc-context', () => import('@/test/fakeAuth.ts'))`,
// then pick a state with `setAuth`.
type AuthState = Pick<AuthContextProps, 'isLoading' | 'isAuthenticated' | 'activeNavigator' | 'error' | 'user'>

const actions = {
  signinRedirect: vi.fn(),
  signoutRedirect: vi.fn(),
}

let state: AuthState = signedOut()

export function signedOut(): AuthState {
  return { isLoading: false, isAuthenticated: false, activeNavigator: undefined, error: undefined, user: null }
}

export function signingIn(): AuthState {
  return { ...signedOut(), isLoading: true }
}

export function signedIn(): AuthState {
  return {
    ...signedOut(),
    isAuthenticated: true,
    user: {
      access_token: 'token',
      profile: { sub: 'user-1', name: 'Asha Rao', given_name: 'Asha' },
      state: undefined,
    } as AuthState['user'],
  }
}

export function setAuth(next: AuthState) {
  state = next
}

export function resetAuth() {
  state = signedOut()
  actions.signinRedirect.mockReset()
  actions.signoutRedirect.mockReset()
}

// For a faked API client: the token the signed-in visitor would send, if any.
export function accessToken() {
  return state.user?.access_token
}

export function useAuth() {
  return { ...state, ...actions } as unknown as AuthContextProps
}

export const { signinRedirect, signoutRedirect } = actions
