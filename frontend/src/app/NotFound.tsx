import { Link } from 'react-router'
import MessageCard from './MessageCard.tsx'

function NotFound() {
  return (
    <MessageCard title="Page not found" description="There's nothing at this address.">
      <Link to="/app" className="underline">Back to my account</Link>
    </MessageCard>
  )
}

export default NotFound
