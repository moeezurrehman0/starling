/* SPDX-License-Identifier: MIT */

/**
 * DynamoDB access for the {@code tweets}, {@code likes} and {@code idempotency} tables.
 *
 * <p>Nothing here decides policy. Whether a like may be recorded is the domain's business; whether
 * the write that records it can be lost or duplicated is this package's, and every conditional
 * expression in here exists to answer that second question without a transaction.
 */
@NullMarked
package dev.twitterclone.tweet.persistence;

import org.jspecify.annotations.NullMarked;
