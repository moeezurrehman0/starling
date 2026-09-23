/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.persistence;

import dev.twitterclone.contracts.StreamCheckpointItem;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

/**
 * Where each shard was last read to.
 *
 * <p>Advanced <em>after</em> a batch is handled, never before. The consequence is at-least-once
 * delivery: a crash between processing and checkpointing replays the batch. That is the safe
 * direction — fan-out writes are idempotent, so a replay is wasted work — whereas checkpointing
 * first would give at-most-once and silently lose a tweet from every follower's timeline, with no
 * error anywhere to notice it by.
 */
@Repository
public class CheckpointRepository {

  private final DynamoDbTable<StreamCheckpointItem> checkpoints;

  public CheckpointRepository(DynamoDbTable<StreamCheckpointItem> checkpointsTable) {
    this.checkpoints = checkpointsTable;
  }

  /**
   * The last sequence number this group processed on a shard.
   *
   * @param consumerGroup which consumer
   * @param shardId which shard
   * @return the sequence number, or empty if the shard has never been read
   */
  public Optional<String> lastSequenceNumber(String consumerGroup, String shardId) {
    return Optional.ofNullable(
            checkpoints.getItem(
                Key.builder().partitionValue(consumerGroup).sortValue(shardId).build()))
        .map(StreamCheckpointItem::sequenceNumber);
  }

  /**
   * Records progress on a shard.
   *
   * @param consumerGroup which consumer
   * @param shardId which shard
   * @param sequenceNumber the last record successfully processed
   */
  public void save(String consumerGroup, String shardId, String sequenceNumber) {
    checkpoints.putItem(
        StreamCheckpointItem.builder()
            .consumerGroup(consumerGroup)
            .shardId(shardId)
            .sequenceNumber(sequenceNumber)
            .updatedAt(Instant.now())
            .build());
  }
}
