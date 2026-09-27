import type { EventCategory } from '@/api/events.ts'

// Events have no image of their own, so a card borrows a landing page poster by category,
// the nearest in spirit where the landing page has none for it.
export const categoryArt: Record<EventCategory, string> = {
  MUSIC: '/assets-v9/p-monsoon.webp',
  COMEDY: '/assets-v9/p-openmic.webp',
  THEATRE: '/assets-v9/p-tughlaq.webp',
  DANCE: '/assets-v9/p-tughlaq.webp',
  SPORTS: '/assets-v9/p-kabaddi.webp',
  CONFERENCE: '/assets-v9/p-tanvi.webp',
  WORKSHOP: '/assets-v9/p-tanvi.webp',
  FAMILY: '/assets-v9/p-jazz.webp',
  OTHER: '/assets-v9/p-jazz.webp',
}
