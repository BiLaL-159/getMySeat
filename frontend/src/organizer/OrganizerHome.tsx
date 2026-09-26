import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card.tsx'

// The Organizer area's landing page, until My Venues and My Events arrive (FE4a, FE4b).
function OrganizerHome() {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h1 className="font-display text-4xl font-black uppercase leading-none">Organizer</h1>
        </CardTitle>
        <CardDescription>Your Venues, Events and Shows will be managed here.</CardDescription>
      </CardHeader>
    </Card>
  )
}

export default OrganizerHome
