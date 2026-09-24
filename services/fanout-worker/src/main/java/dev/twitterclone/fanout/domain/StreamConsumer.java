/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.domain;

import dev.twitterclone.contracts.StreamCheckpointItem;
import dev.twitterclone.fanout.config.FanoutProperties;
import dev.twitterclone.fanout.persistence.CheckpointRepository;
import dev.twitterclone.platform.aws.DynamoDbProperties;
import dev.twitterclone.platform.aws.streams.StreamArns;
import dev.twitterclone.platform.aws.streams.StreamReader;
import dev.twitterclone.platform.aws.streams.StreamRecords;
import dev.twitterclone.platform.aws.streams.StreamSource;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * Reads the {@code tweets} stream and hands each new tweet to fan-out.
 *
 * <p>The polling, the iterator lifetime, the checkpoint ordering and the poison-record policy all
 * live in {@link StreamReader}, shared with the search indexer — they are identical for both and
 * are exactly the parts a second copy would get subtly wrong. What is left here is the only thing
 * specific to fan-out: which records matter and what to do with one.
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

  private final StreamReader reader;
  private final FanoutService fanout;
  private final StreamSource streamSource;
  private final FanoutMetrics metrics;

  public StreamConsumer(
      DynamoDbStreamsClient streams,
      CheckpointRepository checkpoints,
      FanoutService fanout,
      FanoutProperties properties,
      StreamArns arns,
      DynamoDbProperties dynamo,
      FanoutMetrics metrics) {
    // Blank resolves to whatever stream the tweets table currently has. LocalStack mints a
    // new ARN every time the stack is recreated, so a pinned value would be stale after the
    // first teardown.
    //
    // A source rather than a one-shot resolve: discovery used to happen here, in the
    // constructor, and a single timed-out DescribeTable left this consumer holding a blank
    // ARN for the life of the process -- running, ready, and silently consuming nothing.
    this.streamSource = arns.source(properties.streamArn(), dynamo.table("tweets"));
    this.reader =
        new StreamReader(
            streams,
            checkpoints,
            StreamCheckpointItem.GROUP_FANOUT,
            streamSource,
            properties.batchSize());
    this.fanout = fanout;
    this.metrics = metrics;
  }

  /**
   * The stream this consumer reads, which knows whether it has been found.
   *
   * @return the stream handle, for readiness reporting
   */
  public StreamSource streamSource() {
    return streamSource;
  }

  /**
   * Reads one batch from every shard.
   *
   * @return how many records were processed across all shards
   */
  public int pollOnce() {
    return reader.pollOnce(this::handle);
  }

  /**
   * The shards currently in the stream.
   *
   * @return the shards
   */
  public List<Shard> shards() {
    return reader.shards();
  }

  private void handle(Record record) {
    Map<String, AttributeValue> image = StreamRecords.insertImage(record).orElse(null);
    if (image == null) {
      return;
    }
    String tweetId = StreamRecords.string(image, TWEET_ID);
    String authorId = StreamRecords.string(image, AUTHOR_ID);
    if (tweetId == null || authorId == null) {
      LOG.warn("stream record has no tweet or author id, skipping");
      metrics.recordMalformed();
      return;
    }
    FanoutService.Result result = metrics.record(() -> fanout.fanOut(tweetId, authorId));
    // After the work, not before. Lag is meant to answer "how stale was the timeline entry when
    // it appeared", so the clock has to stop when the entry exists, not when this worker first
    // laid eyes on the record.
    metrics.recordLag(record.dynamodb().approximateCreationDateTime());
    LOG.debug(
        "tweet {} -> {} ({} timelines)",
        result.tweetId(),
        result.outcome(),
        result.timelinesWritten());
  }
}
