import { expect, test } from '@playwright/test'

// Needs the seeded compose stack (scripts/seed.sh), rebuilt since the API exposes its Date header:
// "The Glass Menagerie" plays at Siri Fort Auditorium, whose Orchestra is Seated at ₹2,000. The
// seed `customer` holds, and releases, two of its Seats.
test('a Customer holds two Seats, finds the Hold again after a reload, and releases it', async ({ page, request }) => {
  const search = await request.get('http://localhost:8080/api/v1/events?q=Glass%20Menagerie')
  const { content } = (await search.json()) as { content: { nextShow: { id: string } }[] }
  const showUrl = `/shows/${content[0].nextShow.id}`

  // A signed-out visitor picks two Seats, and holding them asks them to sign in first.
  await page.goto(showUrl)
  const available = page.getByRole('checkbox', { name: /^Orchestra, row \w+, seat \d+, available$/ })
  await available.first().click()
  await available.first().click()
  await page.getByRole('button', { name: 'Hold' }).click()
  await page.locator('#username').fill('customer')
  await page.locator('#password').fill('password')
  await page.locator('#kc-login').click()

  // Back on the Show with both Seats still picked.
  const selection = page.getByRole('region', { name: 'Your selection' })
  await expect(selection.getByText(/kept the tickets you picked/i)).toBeVisible()
  const picked = page.getByRole('checkbox', { name: /^Orchestra, row \w+, seat \d+, selected$/ })
  await expect(picked).toHaveCount(2)
  const seats = await picked.evaluateAll((all) => all.map((seat) => seat.getAttribute('aria-label')!.replace(/, selected$/, '')))
  await selection.getByRole('button', { name: 'Hold' }).click()

  const hold = page.getByRole('region', { name: 'Your Hold' })
  await expect(hold.getByRole('listitem')).toHaveCount(2)
  await expect(hold.getByRole('timer')).toHaveText(/^(10:00|9:\d\d)$/)
  for (const seat of seats) await expect(page.getByRole('checkbox', { name: `${seat}, in my Hold` })).toBeVisible()

  await page.reload()
  await expect(hold.getByRole('listitem')).toHaveCount(2)
  await expect(hold.getByText('₹4,000')).toBeVisible()
  for (const seat of seats) await expect(page.getByRole('checkbox', { name: `${seat}, in my Hold` })).toBeVisible()

  await hold.getByRole('button', { name: 'Release' }).click()
  await expect(hold.getByRole('status')).toHaveText(/you released this hold/i)
  for (const seat of seats) await expect(page.getByRole('checkbox', { name: `${seat}, available` })).toBeVisible()
})
