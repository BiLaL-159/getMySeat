import type { ReactNode } from 'react'
import { Link, NavLink, useLocation } from 'react-router'
import { useMe, type Role } from '@/api/me.ts'
import { useSession } from '@/auth/session.ts'
import { Button } from '@/components/ui/button.tsx'
import { cn } from '@/lib/utils.ts'
import ThemeToggle from '@/theme/ThemeToggle.tsx'
import './app.css'

// Each role's own area. Everyone signed in is a Customer; Organizer and Admin areas are guarded
// by RequireRole as well, so hiding an entry is never the only protection.
const navEntries: { to: string; label: string; role: Role }[] = [
  { to: '/app', label: 'My account', role: 'CUSTOMER' },
  { to: '/organizer', label: 'Organizer', role: 'ORGANIZER' },
  { to: '/admin', label: 'Admin', role: 'ADMIN' },
]

// The header every app page shares, signed in or not. `offerSignIn` adds a "Sign in" button for a
// signed-out visitor, on pages they may stay on.
function Layout({ offerSignIn = false, children }: { offerSignIn?: boolean; children: ReactNode }) {
  const session = useSession()
  const location = useLocation()

  return (
    <div className="mx-auto flex min-h-svh max-w-3xl flex-col gap-8 px-4 py-12">
      <header className="flex flex-wrap items-center justify-between gap-4">
        {/* A full load, so Tailwind's preflight doesn't follow the visitor onto the landing page. */}
        <Link to="/" reloadDocument className="font-display text-3xl font-black uppercase leading-none">getMySeat</Link>
        {session.status === 'signedIn' && <Nav />}
        <div className="flex items-center gap-2">
          <ThemeToggle />
          {session.status === 'signedIn' && <Button variant="outline" onClick={session.signOut}>Sign out</Button>}
          {offerSignIn && (session.status === 'signedOut' || session.status === 'expired') && (
            <Button variant="outline" onClick={() => session.signIn(location.pathname + location.search)}>Sign in</Button>
          )}
        </div>
      </header>
      <main>{children}</main>
    </div>
  )
}

function Nav() {
  const me = useMe()
  const roles = me.data?.roles ?? []
  const entries = navEntries.filter((entry) => roles.includes(entry.role))
  if (!entries.length) return null

  return (
    <nav aria-label="Main">
      <ul className="flex flex-wrap gap-1">
        {entries.map((entry) => (
          <li key={entry.to}>
            <NavLink
              to={entry.to}
              className={({ isActive }) =>
                cn('rounded-md px-3 py-2 font-mono text-sm hover:bg-accent', isActive && 'bg-accent font-semibold')
              }
            >
              {entry.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}

export default Layout
