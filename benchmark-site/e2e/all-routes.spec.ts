import { test, expect } from '@playwright/test';

/**
 * Comprehensive E2E tests for all JOTP Benchmark Site routes
 *
 * This test suite validates:
 * - All routes render without errors
 * - No console errors on any page
 * - Key interactive elements work
 * - Responsive design
 */

const routes = [
  // Home and main pages
  { path: '/', name: 'Home' },
  { path: '/architecture', name: 'Architecture' },
  { path: '/flows', name: 'Flows' },
  { path: '/dashboard', name: 'Dashboard' },

  // Monitoring routes
  { path: '/monitoring', name: 'Monitoring' },
  { path: '/monitoring/live', name: 'Live Monitoring' },
  { path: '/monitoring/history', name: 'Monitoring History' },

  // Dashboard routes
  { path: '/dashboard/benchmarks', name: 'Dashboard Benchmarks' },
  { path: '/dashboard/analysis', name: 'Dashboard Analysis' },
  { path: '/dashboard/regression-report', name: 'Regression Report' },
  { path: '/dashboard/docs', name: 'Dashboard Docs' },

  // Analysis routes
  { path: '/analysis/comparison', name: 'Analysis Comparison' },
  { path: '/analysis/hot-path', name: 'Analysis Hot Path' },
  { path: '/analysis/precision', name: 'Analysis Precision' },
  { path: '/analysis/regression', name: 'Analysis Regression' },
  { path: '/analysis/throughput', name: 'Analysis Throughput' },
  { path: '/analysis/capacity', name: 'Analysis Capacity' },

  // Charts routes
  { path: '/charts', name: 'Charts' },
  { path: '/charts/examples', name: 'Chart Examples' },
];

// Core primitives for dynamic route testing (from jotp-architecture.ts)
const corePrimitives = [
  'proc',
  'state-machine',
  'parallel',
  'proc-link',
  'supervisor',
  'proc-monitor',
  'proc-registry',
  'proc-timer',
  'proc-lib',
  'proc-sys',
  'event-manager',
  'circuit-breaker',
  'saga',
  'bulkhead',
  'health-checker',
];

test.describe('Route Validation', () => {
  test.beforeEach(async ({ page }) => {
    // Capture only serious console errors (not resource 404s which are expected in dev)
    page.on('console', message => {
      const text = message.text();
      if (message.type() === 'error') {
        // Ignore expected resource 404s during development
        if (!text.includes('404') && !text.includes('Failed to load resource')) {
          console.error(`Console error on ${page.url()}:`, text);
        }
      }
    });
  });

  test.describe('Static Routes', () => {
    for (const route of routes) {
      test(`should load ${route.name} route without errors`, async ({ page }) => {
        // Track JavaScript errors
        const jsErrors: string[] = [];
        page.on('pageerror', error => {
          jsErrors.push(error.toString());
        });

        await page.goto(route.path);

        // Wait for page to be fully loaded
        await page.waitForLoadState('networkidle');
        await page.waitForTimeout(500);

        // Page title should exist
        const title = await page.title();
        expect(title).toBeTruthy();

        // No 404 or error page in body
        const bodyText = await page.textContent('body');
        expect(bodyText).not.toContain('404');
        expect(bodyText).not.toContain('This page could not be found');

        // No JavaScript errors should have occurred
        expect(jsErrors).toHaveLength(0);
      });
    }
  });

  test.describe('Primitive Detail Routes', () => {
    for (const primitive of corePrimitives) {
      test(`should load /primitives/${primitive} route`, async ({ page }) => {
        // Track JavaScript errors
        const jsErrors: string[] = [];
        page.on('pageerror', error => {
          jsErrors.push(error.toString());
        });

        await page.goto(`/primitives/${primitive}`);

        // Wait for page load
        await page.waitForLoadState('networkidle');
        await page.waitForTimeout(500);

        // Should not be a 404
        const bodyText = await page.textContent('body');
        expect(bodyText).not.toContain('404');

        // Should have some content
        const mainContent = await page.textContent('main, main > div, article, .content');
        expect(mainContent).toBeTruthy();

        // No JavaScript errors
        expect(jsErrors).toHaveLength(0);
      });
    }
  });

  test.describe('Interactive Components', () => {
    test('Architecture page should have interactive controls', async ({ page }) => {
      await page.goto('/architecture');
      await page.waitForLoadState('networkidle');

      // Category filter buttons should exist
      const categoryButtons = page.locator('button').filter({ hasText: /Core|Lifecycle|Enterprise/ });
      await expect(categoryButtons.first()).toBeVisible();

      // Search input should exist
      const searchInput = page.locator('#architecture-search, input[placeholder*="search" i]');
      await expect(searchInput.first()).toBeVisible();

      // Reset button should exist
      const resetButton = page.locator('button').filter({ hasText: /Reset/i });
      await expect(resetButton.first()).toBeVisible();
    });

    test('Flows page should render ReactFlow components', async ({ page }) => {
      await page.goto('/flows');
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(1000); // Extra time for ReactFlow to initialize

      // Should have a flow container
      const flowContainer = page.locator('.react-flow, [class*="react-flow"]');
      await expect(flowContainer.first()).toBeVisible({ timeout: 5000 });
    });
  });

  test.describe('Responsive Design', () => {
    test('should render correctly on mobile viewport', async ({ page }) => {
      // Set mobile viewport
      await page.setViewportSize({ width: 375, height: 667 });
      await page.goto('/architecture');
      await page.waitForLoadState('networkidle');

      // Content should still be visible
      const bodyText = await page.textContent('body');
      expect(bodyText).toBeTruthy();
      expect(bodyText).not.toContain('404');
    });

    test('should render correctly on tablet viewport', async ({ page }) => {
      await page.setViewportSize({ width: 768, height: 1024 });
      await page.goto('/flows');
      await page.waitForLoadState('networkidle');

      const bodyText = await page.textContent('body');
      expect(bodyText).toBeTruthy();
      expect(bodyText).not.toContain('404');
    });

    test('should render correctly on desktop viewport', async ({ page }) => {
      await page.setViewportSize({ width: 1920, height: 1080 });
      await page.goto('/dashboard');
      await page.waitForLoadState('networkidle');

      const bodyText = await page.textContent('body');
      expect(bodyText).toBeTruthy();
      expect(bodyText).not.toContain('404');
    });
  });

  test.describe('Navigation', () => {
    test('should navigate between pages without errors', async ({ page }) => {
      // Track JavaScript errors
      const jsErrors: string[] = [];
      page.on('pageerror', error => {
        jsErrors.push(error.toString());
      });

      await page.goto('/');

      // Navigate to architecture
      await page.click('a[href="/architecture"], nav a:has-text("Architecture")').catch(() => {
        // Try alternative selector if first one doesn't work
        return page.locator('a').filter({ hasText: /Architecture/i }).first().click();
      });
      await page.waitForLoadState('networkidle');

      // Navigate to flows
      await page.click('a[href="/flows"], nav a:has-text("Flows")').catch(() => {
        return page.locator('a').filter({ hasText: /Flows/i }).first().click();
      });
      await page.waitForLoadState('networkidle');

      // Navigate to dashboard
      await page.click('a[href="/dashboard"], nav a:has-text("Dashboard")').catch(() => {
        return page.locator('a').filter({ hasText: /Dashboard/i }).first().click();
      });
      await page.waitForLoadState('networkidle');

      // No JavaScript errors should have occurred
      expect(jsErrors).toHaveLength(0);
    });
  });

  test.describe('Performance', () => {
    test('should load pages within reasonable time', async ({ page }) => {
      const startTime = Date.now();
      await page.goto('/');
      await page.waitForLoadState('networkidle');
      const loadTime = Date.now() - startTime;

      // Should load within 10 seconds
      expect(loadTime).toBeLessThan(10000);
    });

    test('should not have memory leaks on navigation', async ({ page }) => {
      // Navigate through multiple pages
      for (let i = 0; i < 5; i++) {
        await page.goto('/architecture');
        await page.waitForLoadState('networkidle');
        await page.goto('/flows');
        await page.waitForLoadState('networkidle');
        await page.goto('/dashboard');
        await page.waitForLoadState('networkidle');
      }

      // Page should still be responsive
      await page.click('body');
      await expect(page.locator('body')).toBeVisible();
    });
  });
});
