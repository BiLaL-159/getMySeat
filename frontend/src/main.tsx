import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import { createBrowserRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { isRetryable } from './api/problem.ts'
import { restoreSession } from './auth/session.ts'
import { isSigninCallback, onSigninCallback, userManager } from './auth/userManager.ts'
import { routes } from './routes.tsx'

const router = createBrowserRouter(routes)
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: (failures, error) => failures < 2 && isRetryable(error) } },
})

// The callback page signs in from the URL instead.
if (!isSigninCallback()) void restoreSession()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider userManager={userManager} onSigninCallback={onSigninCallback} onRemoveUser={() => queryClient.clear()}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>
  </StrictMode>,
)
