import { expireSession, renewSession } from '@/auth/session.ts'
import { userManager } from '@/auth/userManager.ts'
import { config } from '@/config.ts'
import { createApiClient } from './client.ts'

export const api = createApiClient({
  baseUrl: config.apiBaseUrl,
  getAccessToken: async () => (await userManager.getUser())?.access_token,
  reauthenticate: renewSession,
  expireSession,
})
