import type { RouteObject } from 'react-router'

// Each route is lazy so its stylesheet ships in its own chunk: the landing page
// keeps its hand-written CSS, and Tailwind (with its preflight reset) only loads
// on the app routes.
export const routes: RouteObject[] = [
  {
    path: '/',
    lazy: async () => ({ Component: (await import('./landing/Landing.tsx')).default }),
  },
  {
    path: '/app',
    lazy: async () => ({ Component: (await import('./app/Preview.tsx')).default }),
  },
]
