import { Button } from '@/components/ui/button.tsx'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card.tsx'
import { Input } from '@/components/ui/input.tsx'
import ThemeToggle from '@/theme/ThemeToggle.tsx'
import './app.css'

// Placeholder app route: shows the shadcn primitives in the brand theme until
// the real app screens land.
function Preview() {
  return (
    <main className="mx-auto flex min-h-svh max-w-2xl flex-col gap-8 px-4 py-12">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <h1 className="font-display text-5xl font-black uppercase leading-none">Component preview</h1>
        <ThemeToggle />
      </header>

      <Card>
        <CardHeader>
          <CardTitle className="font-display text-2xl font-black uppercase">Tonight</CardTitle>
          <CardDescription className="font-mono">Kabaddi Nights · NSCI Dome, Mumbai</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          <label htmlFor="email" className="text-sm font-medium">Email</label>
          <Input id="email" type="email" placeholder="you@example.com" />
        </CardContent>
        <CardFooter className="flex flex-wrap gap-2">
          <Button>Hold seats</Button>
          <Button variant="secondary">Secondary</Button>
          <Button variant="outline">Outline</Button>
          <Button variant="ghost">Ghost</Button>
        </CardFooter>
      </Card>
    </main>
  )
}

export default Preview
