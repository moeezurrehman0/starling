/* SPDX-License-Identifier: MIT */
package dev.starling.fanout.persistence;

import dev.starling.contracts.StreamCheckpointItem;
import dev.starling.platform.aws.streams.DynamoDbStreamCheckpoints;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

/**
 * Where each shard was last read to.
 *
 * <p>A Spring bean over the shared store rather than a second implementation of it: the
 * checkpoint's shape and its at-least-once ordering are the same for every consumer group, and
 * fan-out has nothing to add to them. What this class contributes is the wiring — a component that
 * picks up the injected {@code stream_checkpoints} table.
 *
 * <p>Progress is advanced <em>after</em> a batch is handled, never before. A crash between
 * processing and checkpointing replays the batch, and replaying a fan-out rewrites identical rows;
 * checkpointing first would silently lose a tweet from every follower's timeline, with no error
 * anywhere to notice it by.
 */
@Repository
public class CheckpointRepository extends DynamoDbStreamCheckpoints {

  public CheckpointRepository(DynamoDbTable<StreamCheckpointItem> checkpointsTable) {
    super(checkpointsTable);
  }
}
