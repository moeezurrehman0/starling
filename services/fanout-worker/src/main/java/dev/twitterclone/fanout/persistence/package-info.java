/* SPDX-License-Identifier: MIT */

/**
 * DynamoDB access for the fan-out worker: followers out, timeline rows in, shard position saved.
 *
 * <p>Split between the enhanced client and the low-level one on purpose. {@code followee-index}
 * projects {@code KEYS_ONLY}, and the enhanced client would map those rows into items with every
 * non-key attribute null — indistinguishable, at the call site, from data loss.
 */
@NullMarked
package dev.twitterclone.fanout.persistence;

import org.jspecify.annotations.NullMarked;
