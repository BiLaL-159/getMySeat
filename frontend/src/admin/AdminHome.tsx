import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card.tsx'

// The Admin area's landing page, until the review queues arrive (FE3, FE4a).
function AdminHome() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h1 className="font-display text-4xl font-black uppercase leading-none">Admin</h1>
        </CardTitle>
        <CardDescription>Organizer Applications and Venues waiting for review will appear here.</CardDescription>
      </CardHeader>
    </Card>
  )
}

export default AdminHome
