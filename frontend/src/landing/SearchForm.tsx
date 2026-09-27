import { useState } from 'react'
import { useNavigate } from 'react-router'
import { landingSearchPath, whenLabels, type When } from './landingSearch.ts'

type Props = { cities: string[] | undefined; city: string; onCityChange: (city: string) => void }

// The hero's search, leading to the /events results. Without the cities it still searches, just
// not by city. The city is the landing page's, since the Tonight strip follows it.
export function SearchForm({ cities, city, onCityChange }: Props) {
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const [when, setWhen] = useState<When>('weekend')

  return (
    <form
      className="stub"
      id="search"
      role="search"
      onSubmit={(e) => {
        e.preventDefault()
        void navigate(landingSearchPath({ q, city, when }, new Date()))
      }}
    >
      <label htmlFor="q-city"><span className="label">City</span>
        <select id="q-city" value={city} onChange={(e) => onCityChange(e.target.value)}>
          <option value="">Any city</option>
          {cities?.map((name) => <option key={name}>{name}</option>)}
        </select>
      </label>
      <label htmlFor="q-what"><span className="label">Who or what</span>
        <input id="q-what" type="text" placeholder="An artist, a comic, a team" autoComplete="off" value={q} onChange={(e) => setQ(e.target.value)} />
      </label>
      <label htmlFor="q-when"><span className="label">When</span>
        <select id="q-when" value={when} onChange={(e) => setWhen(e.target.value as When)}>
          {Object.entries(whenLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
      </label>
      <button type="submit">Find seats</button>
    </form>
  )
}
