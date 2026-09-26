export type Theme = 'light' | 'dark'

// The theme in effect: an explicit <html data-theme>, else the OS preference.
// Keep in step with the dark rules in tokens.css and the dark variant in app.css.
export function currentTheme(): Theme {
  const explicit = document.documentElement.dataset.theme
  if (explicit === 'light' || explicit === 'dark') return explicit
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme
}
