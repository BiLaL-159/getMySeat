import type { ComponentType } from 'react'
import type { RouteObject } from 'react-router'
import type { Role } from './api/me.ts'

// Each route is lazy so its stylesheet ships in its own chunk: the landing page
// keeps its hand-written CSS, and Tailwind (with its preflight reset) only loads
// on the app routes.
const notFound: RouteObject = {
  path: '*',
  lazy: async () => ({ Component: (await import('./app/NotFound.tsx')).default }),
}

// An area only `role` may enter, however deep the address.
function guarded(path: string, role: Role, load: () => Promise<{ default: ComponentType }>): RouteObject {
  return {
    path,
    lazy: async () => {
      const { default: RequireRole } = await import('./app/RequireRole.tsx')
      return { element: <RequireRole role={role} /> }
    },
    children: [{ index: true, lazy: async () => ({ Component: (await load()).default }) }, notFound],
  }
}

export const routes: RouteObject[] = [
  {
    path: '/',
    lazy: async () => ({ Component: (await import('./landing/Landing.tsx')).default }),
  },
  {
    path: '/auth/callback',
    lazy: async () => ({ Component: (await import('./app/SignInCallback.tsx')).default }),
  },
  {
    // The public app: the same header, but no forced sign-in.
    lazy: async () => ({ Component: (await import('./app/PublicShell.tsx')).default }),
    children: [
      {
        path: '/shows/:id',
        lazy: async () => ({ Component: (await import('./shows/ShowPage.tsx')).default }),
      },
    ],
  },
  {
    // The signed-in app: AppShell signs the visitor in and loads GET /me before any page renders.
    lazy: async () => ({ Component: (await import('./app/AppShell.tsx')).default }),
    children: [
      {
        path: '/app',
        lazy: async () => ({ Component: (await import('./app/Account.tsx')).default }),
      },
      guarded('/organizer', 'ORGANIZER', () => import('./organizer/OrganizerHome.tsx')),
      guarded('/admin', 'ADMIN', () => import('./admin/AdminHome.tsx')),
    ],
  },
]
