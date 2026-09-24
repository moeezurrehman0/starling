/* SPDX-License-Identifier: MIT */
package dev.starling.contracts;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A row of the {@code stream_checkpoints} table: how far one consumer group has read one shard of
 * the {@code tweets} stream.
 *
 * <p>Keyed {@code (consumerGroup, shardId)}. There are exactly two consumer groups — {@code fanout}
 * and {@code search-indexer} — which is also the hard ceiling DynamoDB Streams imposes: no more
 * than two readers per shard without throttling. That constraint is recorded in ADR-0012 and is the
 * sharpest limit the streams decision creates, so it is worth restating wherever the checkpoint is
 * touched.
 *
 * <p>This table is why the project runs no KCL. The Kinesis Client Library would manage leases and
 * checkpoints for us, but its DynamoDB Streams adapter is built on the v1 SDK, which reached end of
 * support in December 2025 and is banned from this repository. Hand-rolling on {@code
 * DescribeStream}/{@code GetShardIterator}/{@code GetRecords} means owning the checkpoint, and this
 * record is that ownership made explicit.
 *
 * <p>The checkpoint is advanced <em>after</em> a batch is processed, so a crash mid-batch replays
 * it. Consumers are therefore required to be idempotent; that is a standing convention of this
 * codebase, not a property of this table.
 */
public record StreamCheckpointItem(
    String consumerGroup, String shardId, String sequenceNumber, Instant updatedAt) {

  /** Consumer group that materialises follower timelines. */
  public static final String GROUP_FANOUT = "fanout";

  /** Consumer group that maintains the PostgreSQL search index. */
  public static final String GROUP_SEARCH = "search-indexer";

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String consumerGroup;
    private @Nullable String shardId;
    private @Nullable String sequenceNumber;
    private @Nullable Instant updatedAt;

    public Builder consumerGroup(String value) {
      this.consumerGroup = value;
      return this;
    }

    public Builder shardId(String value) {
      this.shardId = value;
      return this;
    }

    public Builder sequenceNumber(String value) {
      this.sequenceNumber = value;
      return this;
    }

    public Builder updatedAt(Instant value) {
      this.updatedAt = value;
      return this;
    }

    public StreamCheckpointItem build() {
      return new StreamCheckpointItem(
          Contracts.required(consumerGroup, "consumerGroup"),
          Contracts.required(shardId, "shardId"),
          Contracts.required(sequenceNumber, "sequenceNumber"),
          Contracts.required(updatedAt, "updatedAt"));
    }
  }
}
