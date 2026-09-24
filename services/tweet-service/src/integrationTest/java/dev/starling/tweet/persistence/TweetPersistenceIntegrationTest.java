/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.starling.contracts.IdempotencyItem;
import dev.starling.contracts.Ids;
import dev.starling.contracts.LikeItem;
import dev.starling.contracts.TableSchemas;
import dev.starling.contracts.TweetItem;
import dev.starling.platform.aws.testing.LocalStack;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
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
 * The tweet repositories against a real DynamoDB.
 *
 * <p>These cannot honestly be unit tests. Every guarantee the repositories provide — a conditional
 * put that refuses to overwrite, an atomic {@code ADD} that survives concurrency, a delete whose
 * authorisation <em>is</em> the condition expression — is a property of the database, not of the
 * Java around it. Mocking the SDK would only assert that the code calls the methods it calls.
 */
// PER_CLASS, so the fields below can be instance state. Held statically, this class would be
// nothing but static members and Checkstyle would -- correctly -- read it as a utility class
// that forgot to hide its constructor.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TweetPersistenceIntegrationTest {

  private DynamoDbEnhancedClient enhanced;
  private TweetRepository tweets;
  private LikeRepository likes;
  private IdempotencyRepository idempotency;

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

    tweets = new TweetRepository(enhanced.table("tweets", TableSchemas.TWEET), enhanced, client);
    likes = new LikeRepository(enhanced.table("likes", TableSchemas.LIKE), enhanced);
    idempotency =
        new IdempotencyRepository(enhanced.table("idempotency", TableSchemas.IDEMPOTENCY));
  }

  private static TweetItem tweetOf(String id, String authorId) {
    return TweetItem.builder()
        .tweetId(id)
        .authorId(authorId)
        .text("hello " + id)
        .mediaKeys(List.of())
        .createdAt(Instant.now())
        .likeCount(0)
        .build();
  }

  @Nested
  @DisplayName("tweets")
  class Tweets {

    @Test
    @DisplayName("a created tweet reads back identically")
    void roundTrip() {
      TweetItem original = tweetOf(Ids.newId(), Ids.newId());

      assertThat(tweets.create(original)).isTrue();
      assertThat(tweets.findById(original.tweetId())).contains(original);
    }

    @Test
    @DisplayName("creating the same id twice is refused rather than silently overwriting")
    void createIsConditional() {
      TweetItem original = tweetOf(Ids.newId(), Ids.newId());
      tweets.create(original);

      // The default PutItem is an upsert. Without the condition, a replayed write would
      // destroy the like count the counter has been maintaining.
      assertThat(tweets.create(tweetOf(original.tweetId(), Ids.newId()))).isFalse();
      assertThat(tweets.findById(original.tweetId())).contains(original);
    }

    @Test
    @DisplayName("media keys survive the round trip")
    void mediaKeysRoundTrip() {
      String author = Ids.newId();
      List<String> keys = List.of("media/" + author + "/a", "media/" + author + "/b");
      TweetItem original =
          TweetItem.builder()
              .tweetId(Ids.newId())
              .authorId(author)
              .text("look")
              .mediaKeys(keys)
              .createdAt(Instant.now())
              .likeCount(0)
              .build();
      tweets.create(original);

      assertThat(tweets.findById(original.tweetId()).orElseThrow().mediaKeys()).isEqualTo(keys);
    }

    @Test
    @DisplayName("a batch read returns what exists and omits what does not")
    void batchRead() {
      String author = Ids.newId();
      TweetItem one = tweetOf(Ids.newId(), author);
      TweetItem two = tweetOf(Ids.newId(), author);
      tweets.create(one);
      tweets.create(two);

      Map<String, TweetItem> found =
          tweets.findAllById(List.of(one.tweetId(), Ids.newId(), two.tweetId()));

      assertThat(found).containsOnlyKeys(one.tweetId(), two.tweetId());
    }

    @Test
    @DisplayName("an empty batch does not call DynamoDB")
    void emptyBatch() {
      // BatchGetItem rejects an empty request, so a timeline with no entries would otherwise
      // fail rather than render as empty.
      assertThat(tweets.findAllById(List.of())).isEmpty();
    }

    @Test
    @DisplayName("an author's tweets come back newest first")
    void byAuthorIsDescending() {
      String author = Ids.newId();
      TweetItem oldest = tweetOf(Ids.newId(), author);
      TweetItem middle = tweetOf(Ids.newId(), author);
      TweetItem newest = tweetOf(Ids.newId(), author);
      List.of(oldest, middle, newest).forEach(tweets::create);

      TweetRepository.TweetPage page = tweets.byAuthor(author, Optional.empty(), 10);

      // Ids are UUIDv7, so the index's own sort order is chronological and no separate
      // timestamp key is needed.
      assertThat(page.tweets().stream().map(TweetItem::tweetId))
          .containsExactly(newest.tweetId(), middle.tweetId(), oldest.tweetId());
      assertThat(page.next()).isEmpty();
    }

    @Test
    @DisplayName("paging through an author walks the partition exactly once")
    void byAuthorPages() {
      String author = Ids.newId();
      List<String> ids = List.of(Ids.newId(), Ids.newId(), Ids.newId(), Ids.newId());
      ids.forEach(id -> tweets.create(tweetOf(id, author)));

      TweetRepository.TweetPage first = tweets.byAuthor(author, Optional.empty(), 2);
      assertThat(first.tweets()).hasSize(2);
      assertThat(first.next()).isPresent();

      TweetRepository.TweetPage second = tweets.byAuthor(author, first.next(), 2);
      assertThat(second.tweets()).hasSize(2);

      assertThat(
              Stream.concat(first.tweets().stream(), second.tweets().stream())
                  .map(TweetItem::tweetId))
          .doesNotHaveDuplicates()
          .containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    @DisplayName("the index reads back the full item, not just the keys")
    void indexProjectsEverything() {
      String author = Ids.newId();
      TweetItem original = tweetOf(Ids.newId(), author);
      tweets.create(original);

      // author-index projects ALL, which is what makes the enhanced client legal here. Narrow
      // the projection and this fails, rather than silently returning blank text.
      assertThat(tweets.byAuthor(author, Optional.empty(), 1).tweets())
          .singleElement()
          .isEqualTo(original);
    }

    @Test
    @DisplayName("the like counter moves atomically and reports the new value")
    void adjustLikeCount() {
      TweetItem original = tweetOf(Ids.newId(), Ids.newId());
      tweets.create(original);

      assertThat(tweets.adjustLikeCount(original.tweetId(), 1)).contains(1L);
      assertThat(tweets.adjustLikeCount(original.tweetId(), 1)).contains(2L);
      assertThat(tweets.adjustLikeCount(original.tweetId(), -1)).contains(1L);
    }

    @Test
    @DisplayName("adjusting a deleted tweet's counter does not resurrect it")
    void adjustRefusesToCreate() {
      // UpdateItem creates the item if it is absent. Without the attribute_exists guard, a
      // like arriving after a delete would leave a row with a count and no text.
      assertThat(tweets.adjustLikeCount(Ids.newId(), 1)).isEmpty();
    }

    @Test
    @DisplayName("the author may delete their own tweet")
    void deleteByAuthor() {
      TweetItem original = tweetOf(Ids.newId(), Ids.newId());
      tweets.create(original);

      assertThat(tweets.delete(original.tweetId(), original.authorId())).contains(original);
      assertThat(tweets.findById(original.tweetId())).isEmpty();
    }

    @Test
    @DisplayName("nobody else may")
    void deleteByStranger() {
      TweetItem original = tweetOf(Ids.newId(), Ids.newId());
      tweets.create(original);

      // Ownership is the condition expression, not a prior read. A read-then-delete would
      // leave a window in which the tweet changed between the two calls.
      assertThatThrownBy(() -> tweets.delete(original.tweetId(), Ids.newId()))
          .isInstanceOf(TweetRepository.NotTheAuthorException.class);
      assertThat(tweets.findById(original.tweetId())).isPresent();
    }

    @Test
    @DisplayName("deleting a tweet that is already gone is not an error")
    void deleteAbsent() {
      assertThat(tweets.delete(Ids.newId(), Ids.newId())).isEmpty();
    }
  }

  @Nested
  @DisplayName("likes")
  class Likes {

    @Test
    @DisplayName("a first like is recorded, a second is not")
    void likeIsIdempotent() {
      String tweetId = Ids.newId();
      String userId = Ids.newId();

      assertThat(likes.like(tweetId, userId)).isTrue();
      // The boolean is what drives the counter. If a repeated like returned true, the count
      // would climb every time a client retried.
      assertThat(likes.like(tweetId, userId)).isFalse();
      assertThat(likes.hasLiked(tweetId, userId)).isTrue();
    }

    @Test
    @DisplayName("unliking reports whether there was anything to remove")
    void unlikeReportsOutcome() {
      String tweetId = Ids.newId();
      String userId = Ids.newId();
      likes.like(tweetId, userId);

      assertThat(likes.unlike(tweetId, userId)).isTrue();
      assertThat(likes.unlike(tweetId, userId)).isFalse();
      assertThat(likes.hasLiked(tweetId, userId)).isFalse();
    }

    @Test
    @DisplayName("likes are scoped to the user, not the tweet")
    void likesAreScopedToTheUser() {
      String tweetId = Ids.newId();
      likes.like(tweetId, Ids.newId());
      String other = Ids.newId();

      assertThat(likes.hasLiked(tweetId, other)).isFalse();
      assertThat(likes.like(tweetId, other)).isTrue();
    }

    @Test
    @DisplayName("the time of a like is recorded")
    void likedAt() {
      String tweetId = Ids.newId();
      String userId = Ids.newId();
      Instant before = Instant.now().minusSeconds(1);
      likes.like(tweetId, userId);

      assertThat(likes.likedAt(tweetId, userId)).isPresent();
      assertThat(likes.likedAt(tweetId, userId).orElseThrow()).isAfter(before);
    }

    @Test
    @DisplayName("a batch lookup reports only this user's likes among a page of tweets")
    void likedAmong() {
      String userId = Ids.newId();
      String other = Ids.newId();
      String likedOne = Ids.newId();
      String likedTwo = Ids.newId();
      String notLiked = Ids.newId();
      String likedBySomeoneElse = Ids.newId();

      likes.like(likedOne, userId);
      likes.like(likedTwo, userId);
      likes.like(likedBySomeoneElse, other);

      assertThat(
              likes.likedAmong(List.of(likedOne, likedTwo, notLiked, likedBySomeoneElse), userId))
          .containsExactlyInAnyOrder(likedOne, likedTwo);
    }

    @Test
    @DisplayName("a batch lookup of nothing asks DynamoDB nothing")
    void likedAmongEmpty() {
      // BatchGetItem rejects an empty request, so the short-circuit is load-bearing rather
      // than an optimisation: a page with no tweets on it is perfectly ordinary.
      assertThat(likes.likedAmong(List.of(), Ids.newId())).isEmpty();
    }

    @Test
    @DisplayName("a batch lookup refuses more keys than DynamoDB accepts")
    void likedAmongTooMany() {
      List<String> tooMany = java.util.stream.Stream.generate(Ids::newId).limit(101).toList();

      assertThatThrownBy(() -> likes.likedAmong(tooMany, Ids.newId()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("101");
    }

    @Test
    @DisplayName("the row is keyed exactly as the shared schema says")
    void storedShape() {
      String tweetId = Ids.newId();
      String userId = Ids.newId();
      likes.like(tweetId, userId);

      // Guards against this service and any future consumer disagreeing about the key schema,
      // which DynamoDB would not object to -- it would simply never find the row.
      DynamoDbTable<LikeItem> table = enhanced.table("likes", TableSchemas.LIKE);
      assertThat(table.getItem(Key.builder().partitionValue(tweetId).sortValue(userId).build()))
          .isNotNull();
    }
  }

  @Nested
  @DisplayName("idempotency")
  class Idempotency {

    @Test
    @DisplayName("the first claim wins")
    void firstClaimWins() {
      assertThat(idempotency.claim(Ids.newId(), "k", "body").proceed()).isTrue();
    }

    @Test
    @DisplayName("a second claim with the same body replays rather than proceeding")
    void sameBodyReplays() {
      String user = Ids.newId();
      idempotency.claim(user, "k", "body");

      IdempotencyRepository.Claim second = idempotency.claim(user, "k", "body");

      assertThat(second.proceed()).isFalse();
      assertThat(second.outcome()).isEqualTo(IdempotencyRepository.Claim.Outcome.REPLAYED);
    }

    @Test
    @DisplayName("a second claim with a different body is a mismatch")
    void differentBodyMismatches() {
      String user = Ids.newId();
      idempotency.claim(user, "k", "body");

      assertThat(idempotency.claim(user, "k", "other").outcome())
          .isEqualTo(IdempotencyRepository.Claim.Outcome.MISMATCHED);
    }

    @Test
    @DisplayName("two users may use the same key")
    void keysAreNamespacedByUser() {
      // A client-chosen string is not a global identifier. Without namespacing, the second
      // user here would be handed the first user's result.
      assertThat(idempotency.claim(Ids.newId(), "1", "body").proceed()).isTrue();
      assertThat(idempotency.claim(Ids.newId(), "1", "body").proceed()).isTrue();
    }

    @Test
    @DisplayName("completing records the result without destroying the request hash")
    void completePreservesTheRequestHash() {
      String user = Ids.newId();
      String resultId = Ids.newId();
      idempotency.claim(user, "k", "body");

      idempotency.complete(user, "k", 201, resultId);

      IdempotencyRepository.Claim replay = idempotency.claim(user, "k", "body");
      // Overwriting the request hash with a response hash -- the obvious shortcut -- would
      // make this very retry look like a key conflict.
      assertThat(replay.outcome()).isEqualTo(IdempotencyRepository.Claim.Outcome.REPLAYED);
      IdempotencyItem previous = replay.previous().orElseThrow();
      assertThat(previous.isComplete()).isTrue();
      assertThat(previous.resultId()).isEqualTo(resultId);
      assertThat(previous.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("a claim is in flight until it completes")
    void inFlightUntilCompleted() {
      String user = Ids.newId();
      idempotency.claim(user, "k", "body");

      IdempotencyItem previous = idempotency.claim(user, "k", "body").previous().orElseThrow();

      assertThat(previous.isComplete()).isFalse();
      assertThat(previous.resultId()).isNull();
    }

    @Test
    @DisplayName("releasing a key lets the client correct and retry")
    void releaseFreesTheKey() {
      String user = Ids.newId();
      idempotency.claim(user, "k", "body");

      idempotency.release(user, "k");

      assertThat(idempotency.claim(user, "k", "corrected").proceed()).isTrue();
    }

    @Test
    @DisplayName("completing a key that TTL already reclaimed is a no-op, not a resurrection")
    void completeAfterExpiryDoesNothing() {
      String user = Ids.newId();

      idempotency.complete(user, "never-claimed", 201, Ids.newId());

      assertThat(idempotency.claim(user, "never-claimed", "body").proceed()).isTrue();
    }
  }
}
