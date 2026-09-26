import { useQuery } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { api } from './api.ts'
import type { components } from './schema'

export type Me = components['schemas']['MeResponse']
export type Role = NonNullable<Me['roles']>[number]

export const roleLabels: Record<Role, string> = { CUSTOMER: 'Customer', ORGANIZER: 'Organizer', ADMIN: 'Admin' }

// Who the API thinks the caller is. Roles come from here, never from the token.
export function useMe() {
  const auth = useAuth()
  return useQuery({
    queryKey: ['me', auth.user?.profile.sub],
    enabled: auth.isAuthenticated,
    queryFn: async () => {
      // A failure rejects with an ApiError, so data is there.
      const { data } = await api.GET('/api/v1/me')
      return data!
    },
  })
}
