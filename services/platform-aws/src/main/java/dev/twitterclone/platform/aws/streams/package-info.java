/* SPDX-License-Identifier: MIT */

/**
 * Reading a DynamoDB stream: shard discovery, iterators, checkpoints and the poison-record policy.
 *
 * <p>Shared because there are two consumer groups on the {@code tweets} stream — fan-out and the
 * search index — and everything hard about consuming a stream is identical in both.
 */
@NullMarked
package dev.twitterclone.platform.aws.streams;

import org.jspecify.annotations.NullMarked;
