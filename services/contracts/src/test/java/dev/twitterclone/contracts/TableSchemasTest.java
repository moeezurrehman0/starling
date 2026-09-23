/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.enhanced.dynamodb.TableMetadata;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Round-trip tests for every table schema.
 *
 * <p>These are not ceremony. Two things can break here without any other test noticing, and both
 * are silent in production:
 *
 * <ol>
 *   <li><b>A stored attribute name changes.</b> The writer starts emitting {@code rtw} where the
 *       reader still looks for {@code rt}. DynamoDB is schemaless and accepts it happily; the
 *       reader simply sees null forever. So every name is asserted as a literal string below. If
 *       one of these assertions fails, the correct response is almost never to update the test — it
 *       is to revert the rename and do expand–contract instead.
 *   <li><b>A record gains a component that the schema does not map.</b> The enhanced client does
 *       not check for this: the new field is silently dropped on write. The attribute-set
 *       assertions are exact, not {@code contains}, so an unmapped addition fails here rather than
 *       in an incident.
 * </ol>
 *
 * <p>Each test writes an item to a map and reads it back, because a schema can be wrong in one
 * direction only — a getter wired to the wrong attribute will still round-trip if the setter is
 * wired the same wrong way, which is why the name assertions accompany the equality ones.
 */
@DisplayName("Table schemas")
class TableSchemasTest {

  private static final Instant FIXED = Instant.parse("2026-01-15T09:00:00Z");

  /** Writes an item to its stored form, using the exact call the enhanced client makes. */
  private static <T> Map<String, AttributeValue> store(TableSchema<T> schema, T item) {
    return schema.itemToMap(item, true);
  }

  /** Asserts the stored attribute names, exactly — extras and omissions both fail. */
  private static void assertStoredNames(Map<String, AttributeValue> stored, String... expected) {
    assertThat(stored).containsOnlyKeys(expected);
  }

  @Nested
  @DisplayName("users")
  class Users {

    private UserItem sample() {
      return UserItem.builder()
          .userId("01948f00-0000-7000-8000-000000000001")
          .handle("ada")
          .displayName("Ada Lovelace")
          .bio("Analytical engines.")
          .avatarUrl("https://cdn.example/ada.png")
          .createdAt(FIXED)
          .followerCount(12_000L)
          .celebrity(true)
          .build();
    }

    @Test
    @DisplayName("stores exactly the agreed attribute names")
    void storedNames() {
      assertStoredNames(
          store(TableSchemas.USER, sample()), "uid", "h", "dn", "bio", "av", "ca", "fc", "celeb");
    }

    @Test
    @DisplayName("round-trips without loss")
    void roundTrip() {
      UserItem original = sample();
      assertThat(TableSchemas.USER.mapToItem(store(TableSchemas.USER, original)))
          .isEqualTo(original);
    }

    @Test
    @DisplayName("omits absent optional attributes rather than storing nulls")
    void optionalsOmitted() {
      // ignoreNulls=true is what the enhanced client uses for updates; a stored NULL would
      // overwrite a real bio with nothing, so absence must mean absence.
      UserItem noBio =
          UserItem.builder()
              .userId("u1")
              .handle("ada")
              .displayName("Ada")
              .createdAt(FIXED)
              .followerCount(0L)
              .celebrity(false)
              .build();
      assertThat(store(TableSchemas.USER, noBio)).doesNotContainKeys("bio", "av");
    }

    @Test
    @DisplayName("keeps the celebrity flag stored rather than derived")
    void celebrityIsStored() {
      // A follower count above the threshold with celebrity=false is legal and must survive
      // the round trip: the flag flips on an explicit transition, not implicitly on read.
      UserItem lagging =
          UserItem.builder()
              .userId("u1")
              .handle("ada")
              .displayName("Ada")
              .createdAt(FIXED)
              .followerCount(UserItem.CELEBRITY_THRESHOLD + 1)
              .celebrity(false)
              .build();
      assertThat(TableSchemas.USER.mapToItem(store(TableSchemas.USER, lagging)).celebrity())
          .isFalse();
    }
  }

  @Nested
  @DisplayName("handles")
  class Handles {

    private HandleItem sample() {
      return HandleItem.builder().handle("ada").userId("u1").claimedAt(FIXED).build();
    }

    @Test
    @DisplayName("stores exactly the agreed attribute names")
    void storedNames() {
      assertStoredNames(store(TableSchemas.HANDLE, sample()), "h", "uid", "cla");
    }

    @Test
    @DisplayName("round-trips without loss")
    void roundTrip() {
      HandleItem original = sample();
      assertThat(TableSchemas.HANDLE.mapToItem(store(TableSchemas.HANDLE, original)))
          .isEqualTo(original);
    }
  }

  @Nested
  @DisplayName("tweets")
  class Tweets {

    private TweetItem sample() {
      return TweetItem.builder()
          .tweetId("01948f00-0000-7000-8000-00000000000a")
          .authorId("u1")
          .text("hello")
          .mediaKeys(List.of("media/a.jpg", "media/b.jpg"))
          .replyToTweetId("t0")
          .retweetOfTweetId("t-1")
          .createdAt(FIXED)
          .likeCount(3L)
          .build();
    }

    @Test
    @DisplayName("stores exactly the agreed attribute names")
    void storedNames() {
      assertStoredNames(
          store(TableSchemas.TWEET, sample()), "tid", "aid", "txt", "mk", "rp", "rt", "ca", "lc");
    }

    @Test
    @DisplayName("round-trips without loss, including the media list")
    void roundTrip() {
      TweetItem original = sample();
      TweetItem read = TableSchemas.TWEET.mapToItem(store(TableSchemas.TWEET, original));
      assertThat(read).isEqualTo(original);
      assertThat(read.mediaKeys()).containsExactly("media/a.jpg", "media/b.jpg");
    }

    @Test
    @DisplayName("treats an empty media list as an absent attribute on the way back")
    void emptyMediaList() {
      // DynamoDB has no empty-list problem, but the record normalises null to List.of(), so
      // a tweet stored before media existed must still read back as an empty list, not null.
      TweetItem noMedia =
          TweetItem.builder()
              .tweetId("t1")
              .authorId("u1")
              .text("hello")
              .createdAt(FIXED)
              .likeCount(0L)
              .build();
      assertThat(TableSchemas.TWEET.mapToItem(store(TableSchemas.TWEET, noMedia)).mediaKeys())
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("follows")
  class Follows {

    private FollowItem sample() {
      return FollowItem.builder().followerId("u1").followeeId("u2").createdAt(FIXED).build();
    }

    @Test
    @DisplayName("stores exactly the agreed attribute names")
    void storedNames() {
      assertStoredNames(store(TableSchemas.FOLLOW, sample()), "fwr", "fwe", "ca");
    }

    @Test
    @DisplayName("round-trips without loss")
    void roundTrip() {
      FollowItem original = sample();
      assertThat(TableSchemas.FOLLOW.mapToItem(store(TableSchemas.FOLLOW, original)))
          .isEqualTo(original);
    }

    @Test
    @DisplayName("exposes the reversed key order on the followee index")
    void followeeIndexIsReversed() {
      // The base table answers "who does u1 follow"; the GSI answers "who follows u2". If the
      // two key roles are ever declared the same way round, the GSI silently answers the
      // question the base table already answers, and the follower fan-out query breaks.
      TableMetadata meta = TableSchemas.FOLLOW.tableMetadata();
      assertThat(meta.indexPartitionKey(FollowItem.FOLLOWEE_INDEX)).isEqualTo("fwe");
      assertThat(meta.indexSortKey(FollowItem.FOLLOWEE_INDEX)).contains("fwr");
      assertThat(meta.primaryPartitionKey()).isEqualTo("fwr");
      assertThat(meta.primarySortKey()).contains("fwe");
    }
  }

  @Nested
  @DisplayName("timelines")
  class Timelines {

    private TimelineEntryItem sample() {
      return TimelineEntryItem.builder()
          .ownerId("u2")
          .tweetId("t1")
          .authorId("u1")
          .createdAt(FIXED)
          .expiresAt(FIXED.plus(TimelineEntryItem.RETENTION).getEpochSecond())
          .build();
    }

    @Test
    @DisplayName("stores exactly the agreed attribute names")
    void storedNames() {
      assertStoredNames(
          store(TableSchemas.TIMELINE_ENTRY, sample()), "own", "tid", "aid", "ca", "exp");
    }

    @Test
    @DisplayName("round-trips without loss")
    void roundTrip() {
      TimelineEntryItem original = sample();
      assertThat(
              TableSchemas.TIMELINE_ENTRY.mapToItem(store(TableSchemas.TIMELINE_ENTRY, original)))
          .isEqualTo(original);
    }

    @Test
    @DisplayName("stores the TTL as an epoch-second number, which is the only form TTL reads")
    void ttlIsEpochSecondsNumber() {
      // DynamoDB's TTL scanner ignores any attribute that is not of type N, and does so
      // without error. Storing the expiry as an ISO string would disable expiry entirely and
      // the table would grow forever, which is a cost bug nothing else would catch.
      AttributeValue exp = store(TableSchemas.TIMELINE_ENTRY, sample()).get("exp");
      assertThat(exp.n()).isNotNull();
      assertThat(Long.parseLong(exp.n()))
          .isEqualTo(FIXED.plusSeconds(7 * 24 * 3600).getEpochSecond());
    }
  }

  @Nested
  @DisplayName("likes")
  class Likes {

    @Test
    @DisplayName("stores exactly the agreed attribute names and round-trips")
    void storedNamesAndRoundTrip() {
      LikeItem original = LikeItem.builder().tweetId("t1").userId("u1").likedAt(FIXED).build();
      Map<String, AttributeValue> stored = store(TableSchemas.LIKE, original);
      assertStoredNames(stored, "tid", "uid", "la");
      assertThat(TableSchemas.LIKE.mapToItem(stored)).isEqualTo(original);
    }
  }

  @Nested
  @DisplayName("idempotency")
  class Idempotency {

    @Test
    @DisplayName("stores exactly the agreed attribute names and round-trips")
    void storedNamesAndRoundTrip() {
      IdempotencyItem original =
          IdempotencyItem.builder()
              .key("client-key-1")
              .responseHash("sha256:abc")
              .statusCode(201)
              .createdAt(FIXED)
              .expiresAt(FIXED.plus(IdempotencyItem.RETENTION).getEpochSecond())
              .build();
      Map<String, AttributeValue> stored = store(TableSchemas.IDEMPOTENCY, original);
      assertStoredNames(stored, "k", "rh", "sc", "ca", "exp");
      assertThat(TableSchemas.IDEMPOTENCY.mapToItem(stored)).isEqualTo(original);
    }
  }

  @Nested
  @DisplayName("stream checkpoints")
  class Checkpoints {

    @Test
    @DisplayName("stores exactly the agreed attribute names and round-trips")
    void storedNamesAndRoundTrip() {
      StreamCheckpointItem original =
          StreamCheckpointItem.builder()
              .consumerGroup(StreamCheckpointItem.GROUP_FANOUT)
              .shardId("shardId-00000001700000000000-abcdef01")
              .sequenceNumber("100000000000000000001")
              .updatedAt(FIXED)
              .build();
      Map<String, AttributeValue> stored = store(TableSchemas.STREAM_CHECKPOINT, original);
      assertStoredNames(stored, "cg", "sh", "sq", "ua");
      assertThat(TableSchemas.STREAM_CHECKPOINT.mapToItem(stored)).isEqualTo(original);
    }

    @Test
    @DisplayName("keeps the sequence number a string so long precision is not lost")
    void sequenceNumberIsString() {
      // Stream sequence numbers run to about 40 digits. Stored as N they would survive the
      // DynamoDB round trip but not a parse to long, and a truncated checkpoint replays or
      // skips records depending on which way it rounds.
      StreamCheckpointItem item =
          StreamCheckpointItem.builder()
              .consumerGroup(StreamCheckpointItem.GROUP_SEARCH)
              .shardId("shard-1")
              .sequenceNumber("1".repeat(40))
              .updatedAt(FIXED)
              .build();
      assertThat(store(TableSchemas.STREAM_CHECKPOINT, item).get("sq").s()).hasSize(40);
    }
  }

  @Nested
  @DisplayName("key metadata")
  class Keys {

    // Key tags are invisible to itemToMap/mapToItem: an attribute tagged as the partition key
    // round-trips identically to one tagged as nothing at all. The failure only appears when
    // a table is created or a query is built, which in this codebase is at deploy time and in
    // Terraform respectively. These assertions pull that failure back to compile-and-test.

    @Test
    @DisplayName("every table declares the partition key the Terraform expects")
    void partitionKeys() {
      assertThat(TableSchemas.USER.tableMetadata().primaryPartitionKey()).isEqualTo("uid");
      assertThat(TableSchemas.HANDLE.tableMetadata().primaryPartitionKey()).isEqualTo("h");
      assertThat(TableSchemas.TWEET.tableMetadata().primaryPartitionKey()).isEqualTo("tid");
      assertThat(TableSchemas.TIMELINE_ENTRY.tableMetadata().primaryPartitionKey())
          .isEqualTo("own");
      assertThat(TableSchemas.LIKE.tableMetadata().primaryPartitionKey()).isEqualTo("tid");
      assertThat(TableSchemas.IDEMPOTENCY.tableMetadata().primaryPartitionKey()).isEqualTo("k");
      assertThat(TableSchemas.STREAM_CHECKPOINT.tableMetadata().primaryPartitionKey())
          .isEqualTo("cg");
    }

    @Test
    @DisplayName("declares a sort key only on the tables that are ranges")
    void sortKeys() {
      // tweets is keyed on tweetId alone, with no sort key, so that a permalink is a GetItem
      // rather than a query needing the author. Adding a sort key here would be a breaking
      // change to every stored tweet, so the absence is asserted rather than assumed.
      assertThat(TableSchemas.TWEET.tableMetadata().primarySortKey()).isEmpty();
      assertThat(TableSchemas.USER.tableMetadata().primarySortKey()).isEmpty();
      assertThat(TableSchemas.HANDLE.tableMetadata().primarySortKey()).isEmpty();
      assertThat(TableSchemas.IDEMPOTENCY.tableMetadata().primarySortKey()).isEmpty();

      assertThat(TableSchemas.TIMELINE_ENTRY.tableMetadata().primarySortKey()).contains("tid");
      assertThat(TableSchemas.LIKE.tableMetadata().primarySortKey()).contains("uid");
      assertThat(TableSchemas.STREAM_CHECKPOINT.tableMetadata().primarySortKey()).contains("sh");
    }

    @Test
    @DisplayName("declares the author index that serves a profile page")
    void authorIndex() {
      assertThat(TableSchemas.TWEET.tableMetadata().indexPartitionKey(TweetItem.AUTHOR_INDEX))
          .isEqualTo("aid");
    }
  }

  @Nested
  @DisplayName("builder validation")
  class Validation {

    @Test
    @DisplayName("names the missing attribute instead of throwing a bare NPE")
    void missingRequiredAttributeIsNamed() {
      // The enhanced client calls build() deep inside mapToItem, so a bare NPE from a record
      // constructor arrives with a stack trace that points at SDK internals and names nothing
      // useful. Contracts.required exists to make the message say which attribute is absent.
      assertThatThrownBy(() -> UserItem.builder().userId("u1").build())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("handle");
    }

    @Test
    @DisplayName("rejects an item read back from a table missing a required attribute")
    void partialItemFailsLoudly() {
      Map<String, AttributeValue> partial =
          Map.of("uid", AttributeValue.fromS("u1"), "h", AttributeValue.fromS("ada"));
      assertThatThrownBy(() -> TableSchemas.USER.mapToItem(partial))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
