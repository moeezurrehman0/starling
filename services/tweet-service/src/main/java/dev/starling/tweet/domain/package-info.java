/* SPDX-License-Identifier: MIT */

/**
 * Tweet rules: what may be posted, who may delete it, and what a like does to a counter.
 *
 * <p>Everything in here is expressible without a transaction. Where an invariant would have needed
 * one -- a reply outliving its parent, a like outliving its tweet -- the invariant was dropped and
 * the consequence written down, rather than bought at the price of a cross-partition write on the
 * hot path.
 */
@NullMarked
package dev.starling.tweet.domain;

import org.jspecify.annotations.NullMarked;
