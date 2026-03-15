import { test, expect } from '@playwright/test'

test.describe('Groq Chat Integration', () => {
  test.beforeEach(async ({ page }) => {
    // Start from the chat page
    await page.goto('/chat')
    // Wait for page to be ready
    await page.waitForLoadState('networkidle')
  })

  test('should display chat interface', async ({ page }) => {
    // Verify chat page elements exist
    await expect(page.locator('input[placeholder*="message"]')).toBeVisible()
    await expect(page.getByRole('button', { name: /send/i })).toBeVisible()
  })

  test('should send message and receive streaming response from Groq', async ({ page }) => {
    const testMessage = 'What is JOTP? Answer in one sentence.'

    // Type message
    await page.locator('input[placeholder*="message"]').fill(testMessage)

    // Click send
    await page.getByRole('button', { name: /send/i }).click()

    // Wait for response to appear (streaming takes time)
    await page.waitForSelector('text=GPT-OSS-20B', { timeout: 30000 })

    // Verify response appeared
    const responseBubble = page.locator('div.bg-gray-100').last()
    await expect(responseBubble).toBeVisible({ timeout: 30000 })

    // Verify response has content
    const responseText = await responseBubble.locator('div.text-sm').textContent()
    expect(responseText?.length).toBeGreaterThan(10)
  })

  test('should handle multiple message exchange', async ({ page }) => {
    // First message
    await page.locator('input[placeholder*="message"]').fill('Hello')
    await page.getByRole('button', { name: /send/i }).click()
    await page.waitForSelector('text=GPT-OSS-20B', { timeout: 30000 })

    // Second message
    await page.locator('input[placeholder*="message"]').fill('Tell me about supervisor trees')
    await page.getByRole('button', { name: /send/i }).click()

    // Verify second response
    await page.waitForTimeout(2000) // Allow streaming
    const messages = await page.locator('div.bg-gray-100').count()
    expect(messages).toBeGreaterThanOrEqual(2)
  })
})
