/* SPDX-License-Identifier: MIT */

/**
 * Turning a new tweet into rows in other people's timelines.
 *
 * <p>The write-time half of the hybrid timeline. Ordinary authors are fanned out to every follower
 * so the read is a single query; celebrities are deliberately skipped, because one post reaching
 * ten million followers is not a slow write but an outage, and the read path pulls their tweets
 * instead.
 *
 * <p>Stream consumption is hand-rolled rather than delegated to the KCL — see ADR-0012. Delivery is
 * at-least-once by construction: the checkpoint advances after a batch, never before, and every
 * write is an idempotent put.
 */
@NullMarked
package dev.starling.fanout.domain;

import org.jspecify.annotations.NullMarked;
