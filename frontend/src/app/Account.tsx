import { roleLabels, useMe } from '@/api/me.ts'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card.tsx'

// The Customer's home: who the API thinks they are.
function Account() {
  const me = useMe()
  if (!me.data) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h1 className="font-display text-4xl font-black uppercase leading-none">{me.data.name}</h1>
        </CardTitle>
        {me.data.email && <CardDescription className="font-mono">{me.data.email}</CardDescription>}
      </CardHeader>
      <CardContent>
        <ul aria-label="Roles" className="flex flex-wrap gap-2">
          {me.data.roles?.map((role) => (
            <li key={role} className="rounded-full border px-3 py-1 font-mono text-sm">{roleLabels[role]}</li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

export default Account
