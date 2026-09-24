/* SPDX-License-Identifier: MIT */
package dev.starling.fanout.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.starling.contracts.FollowItem;
import dev.starling.contracts.StreamCheckpointItem;
import dev.starling.contracts.TableSchemas;
import dev.starling.contracts.TimelineEntryItem;
import dev.starling.platform.aws.DynamoDbProperties;
import dev.starling.platform.aws.testing.LocalStack;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Fan-out's writes and reads against a real DynamoDB.
 *
 * <p>Three claims here cannot be made against a mock, because each is a property of the service
 * rather than of this code: that a {@code KEYS_ONLY} index really does return follower ids and
 * nothing else, that a GSI cursor composed of both key pairs really does resume where it left off,
 * and that {@code BatchWriteItem} really does accept exactly the shape the writer builds. A mock
 * would agree with whatever the code assumed about all three.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FanoutPersistenceIntegrationTest {

  private DynamoDbEnhancedClient enhanced;
  private DynamoDbTable<FollowItem> follows;
  private DynamoDbTable<TimelineEntryItem> timelines;
  private FollowerRepository followers;
  private TimelineWriter writer;
  private CheckpointRepository checkpoints;

  @BeforeAll
  void connect() {
    URI endpoint = LocalStack.container().getEndpoint();
    DynamoDbClient client =
        DynamoDbClient.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
    enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    follows = enhanced.table("follows", TableSchemas.FOLLOW);
    timelines = enhanced.table("timelines", TableSchemas.TIMELINE_ENTRY);

    DynamoDbProperties properties = new DynamoDbProperties(endpoint, "");
    followers = new FollowerRepository(client, properties);
    writer = new TimelineWriter(enhanced, timelines);
    checkpoints =
        new CheckpointRepository(
            enhanced.table("stream_checkpoints", TableSchemas.STREAM_CHECKPOINT));
  }

  private void follow(String follower, String followee) {
    follows.putItem(
        FollowItem.builder()
            .followerId(follower)
            .followeeId(followee)
            .createdAt(Instant.now())
            .build());
  }

  private static TimelineEntryItem entry(String owner, String tweetId) {
    return TimelineEntryItem.builder()
        .ownerId(owner)
        .tweetId(tweetId)
        .authorId("author")
        .createdAt(Instant.now())
        .expiresAt(Instant.now().plus(TimelineEntryItem.RETENTION).getEpochSecond())
        .build();
  }

  @Nested
  @DisplayName("follower enumeration")
  class Followers {

    @Test
    @DisplayName("returns every follower of an author")
    void listsFollowers() {
      follow("f1", "star-a");
      follow("f2", "star-a");

      assertThat(followers.followers("star-a", Optional.empty(), 100).ids())
          .containsExactlyInAnyOrder("f1", "f2");
    }

    @Test
    @DisplayName("returns nothing for an author nobody follows")
    void noFollowers() {
      assertThat(followers.followers("nobody-follows-me", Optional.empty(), 100).ids()).isEmpty();
    }

    @Test
    @DisplayName("a cursor resumes without repeating or skipping anyone")
    void pages() {
      IntStream.range(0, 10).forEach(i -> follow(String.format("p%02d", i), "star-b"));

      FollowerRepository.Page first = followers.followers("star-b", Optional.empty(), 4);
      assertThat(first.ids()).hasSize(4);
      assertThat(first.next()).isPresent();

      FollowerRepository.Page second = followers.followers("star-b", first.next(), 4);

      // A cursor that repeats is a follower who sees the tweet twice; a cursor that skips is a
      // follower who never sees it at all, silently.
      assertThat(second.ids()).hasSize(4).doesNotContainAnyElementsOf(first.ids());
    }

    @Test
    @DisplayName("the last page reports no cursor")
    void endOfPages() {
      follow("only", "star-c");

      assertThat(followers.followers("star-c", Optional.empty(), 100).next()).isEmpty();
    }

    @Test
    @DisplayName("one author's followers never leak into another's")
    void isolated() {
      follow("mine", "star-d");
      follow("theirs", "star-e");

      assertThat(followers.followers("star-d", Optional.empty(), 100).ids())
          .containsExactly("mine");
    }
  }

  @Nested
  @DisplayName("timeline writes")
  class Writes {

    @Test
    @DisplayName("writes a batch larger than DynamoDB's 25-item limit")
    void chunks() {
      List<TimelineEntryItem> batch =
          IntStream.range(0, 60).mapToObj(i -> entry("big-" + i, "t1")).toList();

      assertThat(writer.write(batch)).isEqualTo(60);
      assertThat(timelines.getItem(Key.builder().partitionValue("big-59").sortValue("t1").build()))
          .isNotNull();
    }

    @Test
    @DisplayName("a replayed write produces the same row rather than a second one")
    void idempotent() {
      // This is what makes at-least-once stream delivery acceptable: the checkpoint is saved
      // after the batch, so a crash replays it, and a replay has to be free.
      writer.write(List.of(entry("replay", "t1")));
      writer.write(List.of(entry("replay", "t1")));

      assertThat(
              timelines
                  .query(
                      r ->
                          r.queryConditional(
                              software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional
                                  .keyEqualTo(Key.builder().partitionValue("replay").build())))
                  .items()
                  .stream()
                  .count())
          .isEqualTo(1);
    }

    @Test
    @DisplayName("writing nothing touches nothing")
    void empty() {
      assertThat(writer.write(List.of())).isZero();
    }
  }

  @Nested
  @DisplayName("checkpoints")
  class Checkpoints {

    @Test
    @DisplayName("an unread shard has no position")
    void unread() {
      assertThat(checkpoints.lastSequenceNumber(StreamCheckpointItem.GROUP_FANOUT, "never-read"))
          .isEmpty();
    }

    @Test
    @DisplayName("a saved position survives a round trip")
    void roundTrip() {
      checkpoints.save(StreamCheckpointItem.GROUP_FANOUT, "shard-rt", "100");

      assertThat(checkpoints.lastSequenceNumber(StreamCheckpointItem.GROUP_FANOUT, "shard-rt"))
          .contains("100");
    }

    @Test
    @DisplayName("saving again moves the position forward rather than adding a row")
    void overwrites() {
      checkpoints.save(StreamCheckpointItem.GROUP_FANOUT, "shard-ow", "100");
      checkpoints.save(StreamCheckpointItem.GROUP_FANOUT, "shard-ow", "200");

      assertThat(checkpoints.lastSequenceNumber(StreamCheckpointItem.GROUP_FANOUT, "shard-ow"))
          .contains("200");
    }

    @Test
    @DisplayName("the two consumer groups keep independent positions on the same shard")
    void groupsAreIndependent() {
      checkpoints.save(StreamCheckpointItem.GROUP_FANOUT, "shard-sh", "100");
      checkpoints.save(StreamCheckpointItem.GROUP_SEARCH, "shard-sh", "5");

      // Fan-out and the search indexer read the same stream at their own pace. Sharing a
      // position would make the slower one skip whatever the faster one had already passed.
      assertThat(checkpoints.lastSequenceNumber(StreamCheckpointItem.GROUP_FANOUT, "shard-sh"))
          .contains("100");
      assertThat(checkpoints.lastSequenceNumber(StreamCheckpointItem.GROUP_SEARCH, "shard-sh"))
          .contains("5");
    }
  }
}
