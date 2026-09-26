import { Link, Outlet } from 'react-router'
import { roleLabels, useMe, type Role } from '@/api/me.ts'
import MessageCard from './MessageCard.tsx'

// Renders its child routes only for a caller whose GET /me roles include `role`. It sits inside
// AppShell, which has already signed the visitor in and loaded /me.
function RequireRole({ role }: { role: Role }) {
  const me = useMe()
  if (me.data?.roles?.includes(role)) return <Outlet />

  return (
    <MessageCard title="Not allowed" description={`This part of getMySeat is for ${roleLabels[role]}s only.`}>
      <Link to="/app" className="underline">Back to my account</Link>
    </MessageCard>
  )
}

export default RequireRole
