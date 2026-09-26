import { useEffect } from 'react'
import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { useMe, type Role } from '@/api/me.ts'
import { useSession } from '@/auth/session.ts'
import { Button } from '@/components/ui/button.tsx'
import { cn } from '@/lib/utils.ts'
import ThemeToggle from '@/theme/ThemeToggle.tsx'
import MessageCard from './MessageCard.tsx'
import { problemMessage } from './problemMessage.ts'
import './app.css'

// Each role's own area. Everyone signed in is a Customer; Organizer and Admin areas are guarded
// by RequireRole as well, so hiding an entry is never the only protection.
const navEntries: { to: string; label: string; role: Role }[] = [
  { to: '/app', label: 'My account', role: 'CUSTOMER' },
  { to: '/organizer', label: 'Organizer', role: 'ORGANIZER' },
  { to: '/admin', label: 'Admin', role: 'ADMIN' },
]

// The signed-in part of the app. Pages render in the Outlet only once GET /me has loaded, so they
// (and RequireRole) can rely on the caller's roles being there.
function AppShell() {
  const session = useSession()
  const { signIn } = session
  const location = useLocation()
  const here = location.pathname + location.search
  const mustSignIn = (session.status === 'signedOut' || session.status === 'expired') && !session.error

  // A signed-out visitor, or one whose session has expired, goes straight to sign-in and comes back
  // here afterwards. After a failed attempt they choose to retry, so a broken sign-in can't loop.
  useEffect(() => {
    if (mustSignIn) signIn(here)
  }, [mustSignIn, signIn, here])

  return (
    <div className="mx-auto flex min-h-svh max-w-3xl flex-col gap-8 px-4 py-12">
      <header className="flex flex-wrap items-center justify-between gap-4">
        {/* A full load, so Tailwind's preflight doesn't follow the visitor onto the landing page. */}
        <Link to="/" reloadDocument className="font-display text-3xl font-black uppercase leading-none">getMySeat</Link>
        {session.status === 'signedIn' && <Nav />}
        <div className="flex items-center gap-2">
          <ThemeToggle />
          {session.status === 'signedIn' && <Button variant="outline" onClick={session.signOut}>Sign out</Button>}
        </div>
      </header>
      <main>
        {session.status === 'signedIn' ? (
          <SignedIn />
        ) : session.status === 'expired' ? (
          <MessageCard title="Your session has expired" description="Taking you to sign in, then back here.">
            <Button onClick={() => signIn(here)}>Sign in again</Button>
          </MessageCard>
        ) : session.error ? (
          <MessageCard title="You're signed out" description={<span role="alert">Sign-in didn&apos;t work. Please try again.</span>}>
            <Button onClick={() => signIn(here)}>Sign in</Button>
          </MessageCard>
        ) : (
          <p className="font-mono text-muted-foreground">Signing you in…</p>
        )}
      </main>
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

function SignedIn() {
  const me = useMe()

  if (me.isPending) return <p className="font-mono text-muted-foreground">Loading your account…</p>
  if (me.isError) {
    return (
      <div className="flex flex-col items-start gap-4">
        <p role="alert" className="text-destructive">We couldn&apos;t load your account. {problemMessage(me.error)}</p>
        <Button variant="outline" onClick={() => void me.refetch()}>Try again</Button>
      </div>
    )
  }
  return <Outlet />
}

export default AppShell
