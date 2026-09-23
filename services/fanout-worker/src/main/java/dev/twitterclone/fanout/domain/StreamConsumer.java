/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.domain;

import dev.twitterclone.contracts.StreamCheckpointItem;
import dev.twitterclone.fanout.config.FanoutProperties;
import dev.twitterclone.fanout.persistence.CheckpointRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * Reads the {@code tweets} stream and hands each new tweet to fan-out.
 *
 * <p>Hand-rolled on {@code DescribeStream} / {@code GetShardIterator} / {@code GetRecords} because
 * the project runs no KCL: the Kinesis Client Library's DynamoDB Streams adapter is built on AWS
 * SDK v1, which is out of support and banned here. Owning the loop means owning the checkpoint, the
 * iterator lifetime and the poison-record policy, all of which are below.
 *
 * <p>The class is a plain component driven by a caller, not a {@code @Scheduled} method. A loop
 * that schedules itself is a loop that cannot be tested without waiting for a clock; this one
 * exposes {@link #pollOnce()} so a test can run exactly one pass.
 */
@Component
public class StreamConsumer {

  private static final Logger LOG = LoggerFactory.getLogger(StreamConsumer.class);

  private static final String TWEET_ID = "tid";
  private static final String AUTHOR_ID = "aid";

  private final DynamoDbStreamsClient streams;
  private final CheckpointRepository checkpoints;
  private final FanoutService fanout;
  private final FanoutProperties properties;

  public StreamConsumer(
      DynamoDbStreamsClient streams,
      CheckpointRepository checkpoints,
      FanoutService fanout,
      FanoutProperties properties) {
    this.streams = streams;
    this.checkpoints = checkpoints;
    this.fanout = fanout;
    this.properties = properties;
  }

  /**
   * Reads one batch from every shard.
   *
   * @return how many records were processed across all shards
   */
  public int pollOnce() {
    if (properties.streamArn().isBlank()) {
      LOG.debug("no stream ARN configured, nothing to consume");
      return 0;
    }
    int processed = 0;
    for (Shard shard : shards()) {
      processed += drain(shard);
    }
    return processed;
  }

  /**
   * The shards currently in the stream.
   *
   * <p>Re-read on every pass rather than cached. Shards split as throughput rises, and a consumer
   * holding a stale list keeps polling a closed shard while the records pile up in its successor —
   * which looks exactly like a working consumer, right up until someone notices the lag.
   *
   * @return the shards
   */
  public List<Shard> shards() {
    return streams
        .describeStream(DescribeStreamRequest.builder().streamArn(properties.streamArn()).build())
        .streamDescription()
        .shards();
  }

  private int drain(Shard shard) {
    String iterator;
    try {
      iterator = iteratorFor(shard);
    } catch (RuntimeException e) {
      LOG.warn("could not obtain an iterator for shard {}", shard.shardId(), e);
      return 0;
    }

    GetRecordsResponse response;
    try {
      response =
          streams.getRecords(
              GetRecordsRequest.builder()
                  .shardIterator(iterator)
                  .limit(properties.batchSize())
                  .build());
    } catch (ExpiredIteratorException e) {
      // Iterators live 15 minutes. One expires whenever a pass takes longer than that, which
      // a large fan-out can. Re-deriving it from the checkpoint on the next pass is correct
      // and loses nothing; treating it as a failure would stall the shard permanently.
      LOG.info("iterator for shard {} expired, will re-derive next pass", shard.shardId(), e);
      return 0;
    }

    List<Record> records = response.records();
    if (records.isEmpty()) {
      return 0;
    }

    String lastSequence = null;
    for (Record record : records) {
      try {
        handle(record);
        lastSequence = record.dynamodb().sequenceNumber();
      } catch (RuntimeException e) {
        // Logged and skipped rather than retried forever. A record this consumer cannot
        // handle will not become handleable by being read again, and blocking on it stalls
        // every later record on the shard -- one bad tweet costing everyone their timeline.
        LOG.error(
            "skipping unprocessable record {} on shard {}",
            record.dynamodb().sequenceNumber(),
            shard.shardId(),
            e);
        lastSequence = record.dynamodb().sequenceNumber();
      }
    }

    if (lastSequence != null) {
      // After the batch, never before. A crash here replays the batch, and replaying a
      // fan-out rewrites identical rows; checkpointing first would drop tweets silently.
      checkpoints.save(StreamCheckpointItem.GROUP_FANOUT, shard.shardId(), lastSequence);
    }
    return records.size();
  }

  private String iteratorFor(Shard shard) {
    Optional<String> checkpoint =
        checkpoints.lastSequenceNumber(StreamCheckpointItem.GROUP_FANOUT, shard.shardId());

    GetShardIteratorRequest.Builder request =
        GetShardIteratorRequest.builder()
            .streamArn(properties.streamArn())
            .shardId(shard.shardId());

    checkpoint.ifPresentOrElse(
        sequence ->
            request
                .shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                .sequenceNumber(sequence),
        // TRIM_HORIZON, not LATEST, for a shard never read before. LATEST would silently
        // discard everything written while this consumer was down, and a deploy is exactly
        // when that window opens.
        () -> request.shardIteratorType(ShardIteratorType.TRIM_HORIZON));

    return streams.getShardIterator(request.build()).shardIterator();
  }

  private void handle(Record record) {
    // INSERT only. MODIFY fires on every like, because the like counter lives on the tweet
    // row -- fanning those out would rewrite every follower's timeline each time anyone
    // tapped a heart, turning a single-item update into millions of writes.
    if (record.eventName() != OperationType.INSERT) {
      return;
    }
    Map<String, AttributeValue> image = record.dynamodb().newImage();
    if (image == null) {
      return;
    }
    String tweetId = string(image.get(TWEET_ID));
    String authorId = string(image.get(AUTHOR_ID));
    if (tweetId == null || authorId == null) {
      LOG.warn("stream record has no tweet or author id, skipping");
      return;
    }
    FanoutService.Result result = fanout.fanOut(tweetId, authorId);
    LOG.debug(
        "tweet {} -> {} ({} timelines)",
        result.tweetId(),
        result.outcome(),
        result.timelinesWritten());
  }

  private static @Nullable String string(@Nullable AttributeValue value) {
    return value == null ? null : value.s();
  }
}
