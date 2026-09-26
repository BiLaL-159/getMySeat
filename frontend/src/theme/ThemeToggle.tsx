import { useState } from 'react'
import { Button } from '@/components/ui/button.tsx'
import { applyTheme, currentTheme, type Theme } from './theme.ts'

function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>(currentTheme)
  const next: Theme = theme === 'dark' ? 'light' : 'dark'

  function toggle() {
    applyTheme(next)
    setTheme(next)
  }

  return (
    <Button variant="outline" onClick={toggle}>
      Switch to {next} theme
    </Button>
  )
}

export default ThemeToggle
