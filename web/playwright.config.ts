import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests against the Compose stack.
 *
 * There is deliberately no `webServer` block. Playwright would start `next dev`, and a dev
 * server is not the thing being tested: the frontend ships as a distroless standalone build
 * with a pruned, build-traced node_modules, and the failure mode that matters -- a module the
 * trace did not see, or a missing .next/static copy -- exists only in the image. The suite
 * therefore runs against `make up-app`, which is also what CI does.
 *
 * WEB_URL and GATEWAY_URL are honoured because the published host ports are overridable (a
 * shared machine often has something on 8080 already).
 */
const WEB_URL = process.env.WEB_URL ?? "http://localhost:3000";

export default defineConfig({
  testDir: "./e2e",
  // The product is eventually consistent by construction: a tweet reaches a follower's home
  // timeline through a DynamoDB stream and a fan-out worker, and reaches search through a
  // second consumer writing Postgres. Assertions on those paths need real time, so the
  // per-expect timeout is raised rather than sprinkling sleeps through the specs.
  expect: { timeout: 20_000 },
  timeout: 90_000,
  // Serial. The specs sign up real users and post real tweets into one shared stack, and the
  // home-timeline assertions depend on who follows whom. Parallel workers against a single
  // backend would interleave those writes and fail in ways that look like product bugs.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL: WEB_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
