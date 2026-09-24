/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import java.util.Optional;

/**
 * Where a consumer group last read to on each shard.
 *
 * <p>An interface rather than the concrete DynamoDB repository so that {@link StreamReader} can be
 * unit-tested without a table, and so that a consumer keeping its progress somewhere else would not
 * have to fork the reader to do it.
 *
 * <p>The contract is at-least-once: progress is recorded <em>after</em> a batch is handled, so a
 * crash replays it. That is the safe direction for every consumer in this system, because each one
 * writes idempotently and a replay is therefore wasted work rather than corruption. Recording
 * progress first would give at-most-once, and the records lost that way disappear with no error
 * anywhere to notice them by.
 */
public interface StreamCheckpoints {

  /**
   * The last sequence number the group processed on a shard.
   *
   * @param consumerGroup which consumer
   * @param shardId which shard
   * @return the sequence number, or empty if this group has never read this shard
   */
  Optional<String> lastSequenceNumber(String consumerGroup, String shardId);

  /**
   * Records progress on a shard.
   *
   * @param consumerGroup which consumer
   * @param shardId which shard
   * @param sequenceNumber the last record successfully processed
   */
  void save(String consumerGroup, String shardId, String sequenceNumber);
}
