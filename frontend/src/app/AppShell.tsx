import { useEffect } from 'react'
import { Outlet, useLocation } from 'react-router'
import { useMe } from '@/api/me.ts'
import { useSession } from '@/auth/session.ts'
import { Button } from '@/components/ui/button.tsx'
import Layout from './Layout.tsx'
import MessageCard from './MessageCard.tsx'
import { problemMessage } from './problemMessage.ts'

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
    <Layout>
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
    </Layout>
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
