import { expect, test, type Page } from '@playwright/test'

// The seed users from infra/keycloak; every seed password is `password`.
const users = [
  { username: 'customer', name: 'Casey Customer', nav: ['My account'] },
  { username: 'organizer', name: 'Olive Organizer', nav: ['My account', 'Organizer'] },
  { username: 'platform-admin', name: 'Ada Admin', nav: ['My account', 'Admin'] },
]

async function signIn(page: Page, username: string) {
  await page.goto('/app')
  // The shell sends a signed-out visitor to Keycloak's login page, whose field ids are stable.
  await page.locator('#username').fill(username)
  await page.locator('#password').fill('password')
  await page.locator('#kc-login').click()
}

for (const { username, name, nav } of users) {
  test(`${username} signs in and sees their own nav`, async ({ page }) => {
    await signIn(page, username)

    await expect(page.getByRole('heading', { name })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link')).toHaveText(nav)
  })
}

test('a Customer is not allowed into the Admin area', async ({ page }) => {
  await signIn(page, 'customer')
  await expect(page.getByRole('heading', { name: 'Casey Customer' })).toBeVisible()

  await page.goto('/admin')

  await expect(page.getByRole('heading', { name: /not allowed/i })).toBeVisible()
})
