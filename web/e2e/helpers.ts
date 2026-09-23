import { expect, type Page } from "@playwright/test";

/**
 * Helpers shared by the specs.
 *
 * Everything here drives the browser rather than the API. Calling the gateway directly would
 * be faster and would also test nothing that the curl smoke script does not already cover --
 * the point of this suite is the path through the server actions, the httpOnly session cookie
 * and the server-rendered pages.
 */

/** A handle no other run will collide with. The stack is long-lived and shared between runs. */
export function uniqueHandle(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 8)}`;
}

export const PASSWORD = "correct horse battery staple";

/** Signs a new user up through the form and waits for the redirect to the timeline. */
export async function signUp(page: Page, handle: string): Promise<void> {
  await page.goto("/signup");
  await page.getByLabel("Handle").fill(handle);
  await page.getByLabel("Display name").fill(`Test ${handle}`);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  // The action sets the session cookie and redirects; landing anywhere else means it failed.
  await page.waitForURL("/", { timeout: 30_000 });
  await expect(page.getByRole("link", { name: `@${handle}` })).toBeVisible();
}

/** Logs an existing user in through the form. */
export async function logIn(page: Page, handle: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Handle").fill(handle);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.waitForURL("/", { timeout: 30_000 });
}

/** Posts a tweet from the composer on the home page. */
export async function post(page: Page, text: string): Promise<void> {
  await page.goto("/");
  await page.getByLabel("Tweet text").fill(text);
  await page.getByRole("button", { name: "Post" }).click();
  // revalidatePath re-renders the page server-side; the textarea clearing is the signal that
  // the action resolved, not that the tweet is durable anywhere downstream.
  await expect(page.getByLabel("Tweet text")).toHaveValue("", { timeout: 30_000 });
}

/**
 * Reloads until `text` appears, or fails.
 *
 * Needed because two of the read paths in this product are fed by asynchronous consumers of a
 * DynamoDB stream, so a write that has returned 200 is genuinely not yet readable. A
 * Playwright auto-waiting assertion does not help here: the page is server-rendered and will
 * not change on its own, so it has to be fetched again. This is the honest shape of the
 * system rather than a flake workaround -- a real client would poll or hold a socket open.
 */
export async function reloadUntilVisible(page: Page, text: string, attempts = 15): Promise<void> {
  for (let i = 0; i < attempts; i++) {
    await page.reload();
    if (await page.getByText(text, { exact: false }).count()) {
      await expect(page.getByText(text, { exact: false }).first()).toBeVisible();
      return;
    }
    await page.waitForTimeout(2_000);
  }
  throw new Error(`"${text}" never appeared on ${page.url()} after ${attempts} reloads`);
}
