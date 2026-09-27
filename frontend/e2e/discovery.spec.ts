import { expect, test } from '@playwright/test'

// Needs the seeded compose stack (scripts/seed.sh): "The Glass Menagerie" plays at Siri Fort
// Auditorium in New Delhi 9 to 11 days from the seeding, with a Seated Orchestra at ₹2,000 and a
// General Admission Gallery at ₹800.
test('a visitor searches from the landing page and finds a Show with its prices', async ({ page }) => {
  await page.goto('/')

  const search = page.getByRole('search')
  await search.getByLabel('City').selectOption('New Delhi')
  await search.getByLabel('Who or what').fill('Glass Menagerie')
  await search.getByLabel('When').selectOption('Next 30 days')
  await search.getByRole('button', { name: 'Find seats' }).click()

  await expect(page).toHaveURL(/\/events\?q=Glass\+Menagerie&city=New\+Delhi&from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}$/)
  await page.getByRole('list', { name: 'Events' }).getByRole('link', { name: 'The Glass Menagerie' }).click()

  await expect(page.getByRole('heading', { level: 1, name: 'The Glass Menagerie' })).toBeVisible()
  await page.getByRole('list', { name: 'Shows' }).getByRole('link').first().click()

  await expect(page).toHaveURL(/\/shows\/[\w-]+$/)
  await expect(page.getByText('Siri Fort Auditorium')).toBeVisible()
  const sections = page.getByRole('list', { name: 'Sections' }).getByRole('listitem')
  await expect(sections).toHaveCount(2)
  await expect(sections.nth(0)).toContainText('Orchestra')
  await expect(sections.nth(0)).toContainText('₹2,000')
  await expect(sections.nth(1)).toContainText('Gallery')
  await expect(sections.nth(1)).toContainText('₹800')
})
