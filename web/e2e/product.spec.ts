import { expect, test } from "@playwright/test";
import { logIn, post, reloadUntilVisible, signUp, uniqueHandle } from "./helpers";

/**
 * The product, through the browser, against the built images.
 *
 * One spec, run serially, because the interesting assertions are about state that one step
 * creates and a later one reads: a follow that has to exist before fan-out can deliver, a
 * tweet that has to be indexed before search can find it. Splitting these into independent
 * tests would mean re-creating the world in each, which is slower and tests less.
 */
test.describe.configure({ mode: "serial" });

const alice = uniqueHandle("alice");
const bob = uniqueHandle("bob");
const TWEET = `distroless and dynamo ${Date.now()}`;
const FANNED_OUT = `fanned out ${Date.now()}`;

test("a signed-out visitor sees the landing page and no composer", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("See what's happening")).toBeVisible();
  // The composer is server-rendered only for a session holder, so its absence is evidence the
  // page really is being rendered signed-out and not just hiding things in CSS.
  await expect(page.getByLabel("Tweet text")).toHaveCount(0);
  await expect(
    page.getByRole("navigation").getByRole("link", { name: "Sign in" }),
  ).toBeVisible();
});

test("a new user can sign up, and is signed in afterwards", async ({ page }) => {
  await signUp(page, alice);
  // Signed in means the composer exists, which no amount of client-side state could fake:
  // the page is rendered on the server from the httpOnly cookie.
  await expect(page.getByLabel("Tweet text")).toBeVisible();
});

test("the session survives a full page load", async ({ page }) => {
  await logIn(page, alice);
  await page.goto("/");
  await expect(page.getByRole("link", { name: `@${alice}` })).toBeVisible();
});

test("a tweet posted through the composer appears on its author's profile", async ({ page }) => {
  await logIn(page, alice);
  await post(page, TWEET);
  // The author timeline is a direct DynamoDB query, not a stream consumer, so this is the one
  // read path that is immediately consistent.
  await page.goto(`/u/${alice}`);
  await expect(page.getByText(TWEET)).toBeVisible();
});

test("the tweet becomes searchable once the indexer has consumed the stream", async ({ page }) => {
  await page.goto(`/search?q=${encodeURIComponent("distroless")}`);
  // Search is Postgres, fed asynchronously from the DynamoDB stream. Reaching it at all also
  // proves the Flyway migration ran -- without it this endpoint threw, and Spring's ERROR
  // dispatch reported the throw to the browser as a 401.
  await reloadUntilVisible(page, TWEET);
});

test("a second user can follow the first and then receives their next tweet on home", async ({
  page,
}) => {
  await signUp(page, bob);

  await page.goto(`/u/${alice}`);
  await page.getByRole("button", { name: "Follow" }).click();
  await expect(page.getByRole("button", { name: "Following" })).toBeVisible();

  // The follow has to precede the tweet. Fan-out is write-time: posting resolves the author's
  // followers and writes one timeline row each, so a follow that arrives afterwards cannot
  // retroactively deliver anything already posted. That is the design, not a defect -- but it
  // does mean a new follower sees an empty home until the people they follow post again, and
  // the read-time backfill that would fix it does not exist yet for non-celebrity authors.
  // Asserting the wrong order here would have reported a working product as broken.
  const context = await page.context().browser()!.newContext();
  const aliceTab = await context.newPage();
  await logIn(aliceTab, alice);
  await post(aliceTab, FANNED_OUT);
  await context.close();

  // Home is the fan-out path end to end: post -> DynamoDB stream -> worker -> a timeline row
  // per follower. It is the slowest thing in the product and the only assertion that exercises
  // the worker at all.
  await page.goto("/");
  await reloadUntilVisible(page, FANNED_OUT);
});

test("a like is counted and survives a reload", async ({ page }) => {
  await logIn(page, bob);
  await page.goto(`/u/${alice}`);
  await expect(page.getByText(TWEET)).toBeVisible();

  const like = page.getByRole("button", { name: "Like" }).first();
  await like.click();
  await expect(page.getByRole("button", { name: "Unlike" }).first()).toBeVisible();

  // Reload rather than trust the optimistic state: the count has to have been persisted.
  await page.reload();
  await expect(page.getByRole("button", { name: "Unlike" }).first()).toBeVisible();
});

test("logging out clears the session", async ({ page }) => {
  await logIn(page, bob);
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL("/login");
  await page.goto("/");
  await expect(page.getByLabel("Tweet text")).toHaveCount(0);
});
