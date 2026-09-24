/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * A polling reader for one DynamoDB stream and one consumer group.
 *
 * <p>Hand-rolled on {@code DescribeStream} / {@code GetShardIterator} / {@code GetRecords} because
 * the project runs no KCL: the Kinesis Client Library's DynamoDB Streams adapter is built on AWS
 * SDK v1, which is out of support and banned here. Owning the loop means owning the checkpoint, the
 * iterator lifetime and the poison-record policy — and those three decisions, not the polling, are
 * what a stream consumer actually is.
 *
 * <p>Shared rather than written once per consumer. There are two consumer groups on the {@code
 * tweets} stream — fan-out and the search index — and the parts worth getting right are identical
 * in both. A second copy would drift on the subtle clauses first: it is the expired-iterator branch
 * and the checkpoint ordering that a copy gets wrong, and both failures are silent.
 *
 * <p>Driven by a caller rather than by {@code @Scheduled}. A loop that schedules itself cannot be
 * tested without waiting for a clock; {@link #pollOnce} runs exactly one pass.
 */
public class StreamReader {

  private static final Logger LOG = LoggerFactory.getLogger(StreamReader.class);

  /** What to do with a record. Implementations may throw; see {@link #pollOnce}. */
  @FunctionalInterface
  public interface RecordHandler {

    /**
     * Handles one stream record.
     *
     * @param record the record
     */
    void handle(Record record);
  }

  private final DynamoDbStreamsClient streams;
  private final StreamCheckpoints checkpoints;
  private final String consumerGroup;
  private final Supplier<String> streamArn;
  private final int batchSize;

  /**
   * Creates a reader against a fixed ARN.
   *
   * @param streams the Streams client
   * @param checkpoints where progress is kept
   * @param consumerGroup this consumer's name, which partitions the checkpoint table
   * @param streamArn the stream to read, or blank to disable the reader
   * @param batchSize the {@code GetRecords} limit
   */
  public StreamReader(
      DynamoDbStreamsClient streams,
      StreamCheckpoints checkpoints,
      String consumerGroup,
      String streamArn,
      int batchSize) {
    this(streams, checkpoints, consumerGroup, () -> streamArn, batchSize);
  }

  /**
   * Creates a reader against an ARN that may not be known yet.
   *
   * <p>The supplier is consulted on every pass rather than once at construction. Resolving the
   * stream is a network call, and a consumer built at start-up is built at the moment its
   * dependencies are least likely to answer; a reader that captured the result of one failed
   * attempt would idle forever while looking healthy. See {@link StreamSource}.
   *
   * @param streams the Streams client
   * @param checkpoints where progress is kept
   * @param consumerGroup this consumer's name, which partitions the checkpoint table
   * @param streamArn supplies the stream to read, returning blank while it is unknown
   * @param batchSize the {@code GetRecords} limit
   */
  public StreamReader(
      DynamoDbStreamsClient streams,
      StreamCheckpoints checkpoints,
      String consumerGroup,
      Supplier<String> streamArn,
      int batchSize) {
    this.streams = streams;
    this.checkpoints = checkpoints;
    this.consumerGroup = consumerGroup;
    this.streamArn = streamArn;
    this.batchSize = batchSize;
  }

  /**
   * Reads one batch from every shard.
   *
   * <p>A handler that throws does not stop the pass. The record is logged and skipped, and the
   * checkpoint advances past it: a record this consumer cannot handle will not become handleable by
   * being read again, and blocking on it stalls every later record on the shard — one bad tweet
   * costing everyone their timeline.
   *
   * @param handler what to do with each record
   * @return how many records were read across all shards
   */
  public int pollOnce(RecordHandler handler) {
    String arn = streamArn.get();
    if (arn.isBlank()) {
      LOG.debug("no stream ARN configured for group {}, nothing to consume", consumerGroup);
      return 0;
    }
    int processed = 0;
    for (Shard shard : shards(arn)) {
      processed += drain(arn, shard, handler);
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
    return shards(streamArn.get());
  }

  private List<Shard> shards(String arn) {
    return streams
        .describeStream(DescribeStreamRequest.builder().streamArn(arn).build())
        .streamDescription()
        .shards();
  }

  private int drain(String arn, Shard shard, RecordHandler handler) {
    String iterator;
    try {
      iterator = iteratorFor(arn, shard);
    } catch (RuntimeException e) {
      LOG.warn("could not obtain an iterator for shard {}", shard.shardId(), e);
      return 0;
    }

    GetRecordsResponse response;
    try {
      response =
          streams.getRecords(
              GetRecordsRequest.builder().shardIterator(iterator).limit(batchSize).build());
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
        handler.handle(record);
      } catch (RuntimeException e) {
        LOG.error(
            "skipping unprocessable record {} on shard {}",
            record.dynamodb().sequenceNumber(),
            shard.shardId(),
            e);
      }
      lastSequence = record.dynamodb().sequenceNumber();
    }

    // After the batch, never before. A crash here replays the batch, and every consumer in
    // this system writes idempotently, so a replay is wasted work; checkpointing first would
    // drop records silently.
    checkpoints.save(consumerGroup, shard.shardId(), lastSequence);
    return records.size();
  }

  private String iteratorFor(String arn, Shard shard) {
    Optional<String> checkpoint = checkpoints.lastSequenceNumber(consumerGroup, shard.shardId());

    GetShardIteratorRequest.Builder request =
        GetShardIteratorRequest.builder().streamArn(arn).shardId(shard.shardId());

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
}
