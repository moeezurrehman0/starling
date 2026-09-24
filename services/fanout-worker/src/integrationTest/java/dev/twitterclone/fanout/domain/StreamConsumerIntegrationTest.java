/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.twitterclone.contracts.FollowItem;
import dev.twitterclone.contracts.TableSchemas;
import dev.twitterclone.contracts.TimelineEntryItem;
import dev.twitterclone.contracts.TweetItem;
import dev.twitterclone.contracts.UserItem;
import dev.twitterclone.fanout.config.FanoutProperties;
import dev.twitterclone.fanout.persistence.CheckpointRepository;
import dev.twitterclone.fanout.persistence.FollowerRepository;
import dev.twitterclone.fanout.persistence.TimelineWriter;
import dev.twitterclone.platform.aws.DynamoDbProperties;
import dev.twitterclone.platform.aws.streams.StreamArns;
import dev.twitterclone.platform.aws.testing.LocalStack;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * The whole consumer, from a real stream record to a real timeline row.
 *
 * <p>Everything else about fan-out is unit tested against mocks, which is enough to pin the
 * decisions but not enough to know the thing works: the unit tests assert that this code reads
 * {@code tid} and {@code aid} out of a {@code NEW_IMAGE} it constructed itself. This test is the
 * only place the attribute names in the stream record are the ones DynamoDB actually emits, and the
 * only place the shard iterator lifecycle is real.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamConsumerIntegrationTest {

  private static final FanoutProperties PROPERTIES_TEMPLATE =
      new FanoutProperties("", Duration.ofMillis(10), Duration.ofMillis(10), 100, 50_000, true);

  private DynamoDbTable<TweetItem> tweets;
  private DynamoDbTable<UserItem> users;
  private DynamoDbTable<FollowItem> follows;
  private DynamoDbTable<TimelineEntryItem> timelines;
  private final MeterRegistry meters = new SimpleMeterRegistry();

  private StreamConsumer consumer;

  @BeforeAll
  void connect() {
    URI endpoint = LocalStack.container().getEndpoint();
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    DynamoDbClient client =
        DynamoDbClient.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .build();
    DynamoDbStreamsClient streams =
        DynamoDbStreamsClient.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_1)
            .credentialsProvider(credentials)
            .build();
    DynamoDbEnhancedClient enhanced =
        DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();

    tweets = enhanced.table("tweets", TableSchemas.TWEET);
    users = enhanced.table("users", TableSchemas.USER);
    follows = enhanced.table("follows", TableSchemas.FOLLOW);
    timelines = enhanced.table("timelines", TableSchemas.TIMELINE_ENTRY);

    String streamArn =
        client
            .describeTable(DescribeTableRequest.builder().tableName("tweets").build())
            .table()
            .latestStreamArn();
    assertThat(streamArn).as("the tweets table must have a stream").isNotBlank();

    DynamoDbProperties properties = new DynamoDbProperties(endpoint, "");
    // Left blank on purpose: this exercises StreamArns discovery against a real table, which is
    // how Compose and the sandbox run (LocalStack mints a new ARN on every stack recreate).
    FanoutProperties fanoutProperties =
        new FanoutProperties(
            "",
            PROPERTIES_TEMPLATE.pollInterval(),
            PROPERTIES_TEMPLATE.idleBackoff(),
            PROPERTIES_TEMPLATE.batchSize(),
            PROPERTIES_TEMPLATE.maxFollowersPerTweet(),
            true);
    FanoutService fanout =
        new FanoutService(
            users,
            new FollowerRepository(client, properties),
            new TimelineWriter(enhanced, timelines),
            fanoutProperties);
    consumer =
        new StreamConsumer(
            streams,
            new CheckpointRepository(
                enhanced.table("stream_checkpoints", TableSchemas.STREAM_CHECKPOINT)),
            fanout,
            fanoutProperties,
            new StreamArns(client),
            properties,
            new FanoutMetrics(meters));
  }

  private void user(String id, boolean celebrity) {
    users.putItem(
        UserItem.builder()
            .userId(id)
            .handle(id)
            .displayName(id)
            .followerCount(0L)
            .celebrity(celebrity)
            .createdAt(Instant.now())
            .build());
  }

  private void tweet(String tweetId, String authorId) {
    tweets.putItem(
        TweetItem.builder()
            .tweetId(tweetId)
            .authorId(authorId)
            .text("hello")
            .createdAt(Instant.now())
            .likeCount(0L)
            .build());
  }

  /**
   * Polls until the expected effect appears.
   *
   * <p>A record is not readable the instant the write returns, and a fixed number of immediate
   * passes makes the test depend on how fast the container happens to be today.
   */
  private void drainUntil(java.util.function.BooleanSupplier done) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      consumer.pollOnce();
      if (done.getAsBoolean()) {
        return;
      }
      sleep();
    }
    throw new AssertionError("the stream never produced the expected effect within 30s");
  }

  /**
   * Polls for a fixed window, for assertions that nothing happens.
   *
   * <p>There is no state to wait for when the correct outcome is silence, so the only honest test
   * is to give the consumer real time to get it wrong.
   */
  private void drainQuietly() {
    for (int pass = 0; pass < 20; pass++) {
      consumer.pollOnce();
      sleep();
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private boolean timelineHas(String owner, String tweetId) {
    return timelines.getItem(Key.builder().partitionValue(owner).sortValue(tweetId).build())
        != null;
  }

  @Test
  @DisplayName("an inserted tweet reaches its followers' timelines")
  void endToEnd() {
    user("sc-author", false);
    follows.putItem(
        FollowItem.builder()
            .followerId("sc-follower")
            .followeeId("sc-author")
            .createdAt(Instant.now())
            .build());

    tweet("sc-tweet-1", "sc-author");
    drainUntil(() -> timelineHas("sc-follower", "sc-tweet-1"));

    // The attribute names are the load-bearing part: the consumer reads "tid" and "aid" out of
    // the NEW_IMAGE, and nothing but a real stream record proves those are what DynamoDB emits.
    assertThat(timelineHas("sc-follower", "sc-tweet-1")).isTrue();
    assertThat(timelineHas("sc-author", "sc-tweet-1")).isTrue();
  }

  @Test
  @DisplayName("a celebrity's tweet is not fanned out")
  void celebrityIsSkipped() {
    user("sc-star", true);
    follows.putItem(
        FollowItem.builder()
            .followerId("sc-fan")
            .followeeId("sc-star")
            .createdAt(Instant.now())
            .build());

    tweet("sc-tweet-2", "sc-star");
    drainUntil(() -> timelineHas("sc-star", "sc-tweet-2"));

    assertThat(timelineHas("sc-fan", "sc-tweet-2")).isFalse();
    assertThat(timelineHas("sc-star", "sc-tweet-2")).isTrue();
  }

  @Test
  @DisplayName("a like does not re-fan the tweet")
  void likesDoNotFanOut() {
    user("sc-liked", false);
    tweet("sc-tweet-3", "sc-liked");
    drainUntil(() -> timelineHas("sc-liked", "sc-tweet-3"));

    follows.putItem(
        FollowItem.builder()
            .followerId("sc-latecomer")
            .followeeId("sc-liked")
            .createdAt(Instant.now())
            .build());
    tweets.updateItem(
        TweetItem.builder()
            .tweetId("sc-tweet-3")
            .authorId("sc-liked")
            .text("hello")
            .createdAt(Instant.now())
            .likeCount(1L)
            .build());
    drainQuietly();

    // The MODIFY arrives as a full NEW_IMAGE and is indistinguishable from an insert except by
    // its event type. If the filter were wrong, this follower would appear -- and in production
    // every like would rewrite every follower's timeline.
    assertThat(timelineHas("sc-latecomer", "sc-tweet-3")).isFalse();
  }

  @Test
  @DisplayName("the shard position advances, so a second pass reprocesses nothing")
  void checkpointAdvances() {
    user("sc-cp", false);
    tweet("sc-tweet-4", "sc-cp");
    drainUntil(() -> timelineHas("sc-cp", "sc-tweet-4"));

    timelines.deleteItem(Key.builder().partitionValue("sc-cp").sortValue("sc-tweet-4").build());
    drainQuietly();

    // Deleting the row and re-polling is the clearest way to see the checkpoint working: if the
    // consumer had not advanced, TRIM_HORIZON would replay the insert and put the row back.
    assertThat(timelineHas("sc-cp", "sc-tweet-4")).isFalse();
  }

  @Test
  @DisplayName("an idle stream yields nothing and costs nothing")
  void idle() {
    drainQuietly();

    assertThat(consumer.pollOnce()).isZero();
  }

  @Test
  @DisplayName("tweets inserted while the consumer was stopped are still delivered")
  void catchesUpAfterDowntime() {
    user("sc-down", false);
    follows.putItem(
        FollowItem.builder()
            .followerId("sc-waiting")
            .followeeId("sc-down")
            .createdAt(Instant.now())
            .build());

    // Written with nobody polling, which is exactly what a deploy looks like. TRIM_HORIZON
    // rather than LATEST is what makes this survivable.
    List.of("sc-tweet-5", "sc-tweet-6").forEach(id -> tweet(id, "sc-down"));
    drainUntil(
        () -> timelineHas("sc-waiting", "sc-tweet-5") && timelineHas("sc-waiting", "sc-tweet-6"));

    assertThat(timelineHas("sc-waiting", "sc-tweet-5")).isTrue();
    assertThat(timelineHas("sc-waiting", "sc-tweet-6")).isTrue();
  }
}
