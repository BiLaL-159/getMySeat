// Prices come as paise; whole rupees drop the ".00", as on a ticket.
const wholeRupees = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 })
const rupeesAndPaise = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' })

export function formatPrice(amountPaise: number) {
  return (amountPaise % 100 === 0 ? wholeRupees : rupeesAndPaise).format(amountPaise / 100)
}

// A Show happens where its Venue is, so its time is always shown in the Venue's time zone,
// whatever the visitor's own clock says.
export function formatShowTime(startsAt: string, timeZone: string) {
  return new Intl.DateTimeFormat('en-IN', {
    timeZone,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZoneName: 'short',
  }).format(new Date(startsAt))
}

// Part of a Show's start at its Venue, such as its time or its day of the month.
export function formatShowPart(startsAt: string, timeZone: string, part: Intl.DateTimeFormatOptions) {
  return new Intl.DateTimeFormat('en-IN', { timeZone, ...part }).format(new Date(startsAt))
}
