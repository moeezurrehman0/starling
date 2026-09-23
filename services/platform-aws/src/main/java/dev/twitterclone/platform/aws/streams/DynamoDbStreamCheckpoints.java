/* SPDX-License-Identifier: MIT */
package dev.twitterclone.platform.aws.streams;

import dev.twitterclone.contracts.StreamCheckpointItem;
import java.time.Instant;
import java.util.Optional;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

/**
 * Checkpoints kept in the {@code stream_checkpoints} table.
 *
 * <p>The same table serves every consumer group, partitioned by group name, so adding a consumer
 * adds rows rather than infrastructure. Progress belongs next to the data it describes: a
 * checkpoint in a file or a local volume is lost the first time a pod is rescheduled, and the
 * consumer then silently re-reads the stream from its trim horizon.
 */
public class DynamoDbStreamCheckpoints implements StreamCheckpoints {

  private final DynamoDbTable<StreamCheckpointItem> table;

  /**
   * Creates a store.
   *
   * @param checkpointsTable the {@code stream_checkpoints} table
   */
  public DynamoDbStreamCheckpoints(DynamoDbTable<StreamCheckpointItem> checkpointsTable) {
    this.table = checkpointsTable;
  }

  @Override
  public Optional<String> lastSequenceNumber(String consumerGroup, String shardId) {
    return Optional.ofNullable(
            table.getItem(Key.builder().partitionValue(consumerGroup).sortValue(shardId).build()))
        .map(StreamCheckpointItem::sequenceNumber);
  }

  @Override
  public void save(String consumerGroup, String shardId, String sequenceNumber) {
    table.putItem(
        StreamCheckpointItem.builder()
            .consumerGroup(consumerGroup)
            .shardId(shardId)
            .sequenceNumber(sequenceNumber)
            .updatedAt(Instant.now())
            .build());
  }
}
