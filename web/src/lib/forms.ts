/* SPDX-License-Identifier: MIT */

/**
 * Form state shared by the server actions and the forms that call them.
 *
 * In its own module because a `"use server"` file may export nothing but async functions —
 * every export there becomes a callable server endpoint, so a plain object constant is a build
 * error rather than a style problem.
 */
export interface FormState {
  error: string | null;
  /**
   * Set on each successful submission. The value is never displayed — it exists so that two
   * consecutive successes are distinct objects, which is what lets a controlled form clear
   * itself. Without it, posting twice produces an identical state and nothing re-runs.
   */
  succeededAt?: number;
}

export const NO_ERROR: FormState = { error: null };
