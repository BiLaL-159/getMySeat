import { useState } from 'react'
import { knownCity, tonightCity } from './whatsOn.ts'

const storageKey = 'getmyseat.landing.city'

// Storage can be missing or blocked, as in a private window; the city is then just not kept.
function remembered() {
  try {
    return localStorage.getItem(storageKey)
  } catch {
    return null
  }
}

function remember(city: string) {
  try {
    if (city) localStorage.setItem(storageKey, city)
    else localStorage.removeItem(storageKey)
  } catch {
    // Not kept for the next visit, but still chosen for this one.
  }
}

// The city chosen in the landing page's search form, kept across visits, and the city the
// Tonight strip is for. Until the cities load, or once the remembered one is gone, it's any city.
export function useLandingCity(cities: string[] | undefined) {
  const [chosen, setChosen] = useState(remembered)
  const city = knownCity(chosen, cities)
  return {
    city,
    tonight: tonightCity(city, cities),
    choose(next: string) {
      setChosen(next)
      remember(next)
    },
  }
}
