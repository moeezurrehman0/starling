/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.search;

import dev.starling.contracts.StreamCheckpointItem;
import dev.starling.platform.aws.DynamoDbProperties;
import dev.starling.platform.aws.streams.DynamoDbStreamCheckpoints;
import dev.starling.platform.aws.streams.StreamArns;
import dev.starling.platform.aws.streams.StreamReader;
import dev.starling.platform.aws.streams.StreamRecords;
import dev.starling.platform.aws.streams.StreamSource;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * Keeps the Postgres search index in step with the {@code tweets} table.
 *
 * <p>The second of the two consumer groups on the {@code tweets} stream, and the last one ADR-0012
 * allows: every additional group multiplies the read load on the stream, and two is the number this
 * system can justify. It shares {@link StreamReader} with fan-out, so shard discovery, iterator
 * lifetime, checkpoint ordering and the poison-record policy are the same code in both, not the
 * same intention expressed twice.
 *
 * <p>Fed from the stream rather than written inline by {@code TweetService}. Indexing in the
 * request would couple posting a tweet to Postgres being up and to the index write succeeding —
 * turning a search outage into an outage of the product's core action. The price is that search is
 * eventually consistent, which is recorded as a retraction in ADR-0007 rather than hidden.
 */
@Component
public class TweetIndexer {

  private static final Logger LOG = LoggerFactory.getLogger(TweetIndexer.class);

  private static final String TWEET_ID = "tid";
  private static final String AUTHOR_ID = "aid";
  private static final String TEXT = "txt";
  private static final String CREATED_AT = "ca";

  private final StreamReader reader;
  private final SearchIndex index;
  private final StreamSource streamSource;

  public TweetIndexer(
      DynamoDbStreamsClient streams,
      DynamoDbStreamCheckpoints checkpoints,
      SearchIndex index,
      SearchProperties properties,
      StreamArns arns,
      DynamoDbProperties dynamo) {
    // Blank resolves to the tweets table's current stream; see StreamArns. A source rather
    // than a one-shot resolve so that a dependency which is not up yet at construction time
    // does not permanently disable indexing -- see StreamSource for the incident.
    this.streamSource = arns.source(properties.streamArn(), dynamo.table("tweets"));
    this.reader =
        new StreamReader(
            streams,
            checkpoints,
            StreamCheckpointItem.GROUP_SEARCH,
            streamSource,
            properties.batchSize());
    this.index = index;
  }

  /**
   * The stream this indexer reads, which knows whether it has been found.
   *
   * @return the stream handle, for readiness reporting
   */
  public StreamSource streamSource() {
    return streamSource;
  }

  /**
   * Reads one batch from every shard and indexes what it finds.
   *
   * @return how many records were read
   */
  public int pollOnce() {
    return reader.pollOnce(this::handle);
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
      return;
    }
    // A bare retweet carries no text of its own. Indexing it would put an empty document in
    // the index, which matches nothing and costs a row; skipping it means a retweet is found
    // through the tweet it quotes, which is where the words actually are.
    String text = StreamRecords.string(image, TEXT);
    if (text == null || text.isBlank()) {
      return;
    }
    index.index(tweetId, authorId, text, createdAt(image));
  }

  private static Instant createdAt(Map<String, AttributeValue> image) {
    String raw = StreamRecords.string(image, CREATED_AT);
    if (raw == null) {
      return Instant.now();
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      // Indexed at now rather than dropped. A row with a wrong timestamp is mis-ordered in one
      // page of results; a row that is not there is invisible forever.
      LOG.warn("unparseable createdAt '{}', indexing at the current time", raw, e);
      return Instant.now();
    }
  }
}
