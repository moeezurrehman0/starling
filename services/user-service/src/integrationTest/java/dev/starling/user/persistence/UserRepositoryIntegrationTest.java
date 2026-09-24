/* SPDX-License-Identifier: MIT */
package dev.starling.user.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.starling.contracts.HandleItem;
import dev.starling.contracts.Ids;
import dev.starling.contracts.TableSchemas;
import dev.starling.contracts.UserItem;
import dev.starling.platform.aws.testing.LocalStack;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Exercises the two repositories against a real DynamoDB.
 *
 * <p>Every behaviour asserted here is one the SDK enforces rather than this code: conditional
 * writes, transaction cancellation reasons, atomic counters, GSI projections and paging. Mocking
 * the client would replace all of that with an assertion that the code calls the methods it calls,
 * which is why these tests are here and not in the unit suite.
 */
@DisplayName("user-service repositories against DynamoDB")
// Per-class lifecycle so the repositories can be instance fields. Holding them statically
// would make this an all-static type, which reads as a utility class rather than a fixture.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRepositoryIntegrationTest {

  private UserRepository users;
  private FollowRepository follows;

  @BeforeAll
  void wire() {
    var localstack = LocalStack.container();

    DynamoDbClient client =
        DynamoDbClient.builder()
            .endpointOverride(URI.create(localstack.getEndpoint().toString()))
            .region(Region.of(localstack.getRegion()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        localstack.getAccessKey(), localstack.getSecretKey())))
            .build();

    DynamoDbEnhancedClient enhanced =
        DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();

    DynamoDbTable<UserItem> usersTable = enhanced.table("users", TableSchemas.USER);
    DynamoDbTable<HandleItem> handlesTable = enhanced.table("handles", TableSchemas.HANDLE);
    DynamoDbTable<CredentialItem> credentialsTable =
        enhanced.table("credentials", CredentialItem.SCHEMA);

    users = new UserRepository(enhanced, usersTable, handlesTable, credentialsTable);
    follows = new FollowRepository(enhanced.table("follows", TableSchemas.FOLLOW), client);
  }

  private static UserItem newUser(String handle) {
    return UserItem.builder()
        .userId(Ids.newId().toString())
        .handle(handle)
        .displayName(handle)
        .followerCount(0L)
        .celebrity(false)
        .createdAt(Instant.now())
        .build();
  }

  /** Unique per test, so the shared container does not make one test depend on another. */
  private static String uniqueHandle(String prefix) {
    return prefix + Ids.newId().toString().replace("-", "").substring(0, 12);
  }

  @Nested
  @DisplayName("registration")
  class Registration {

    @Test
    @DisplayName("writes the user, the handle claim and the credential atomically")
    void writesAllThree() {
      UserItem user = newUser(uniqueHandle("ada"));

      users.createWithHandle(user, "$2a$10$hash");

      assertThat(users.findById(user.userId())).contains(user);
      assertThat(users.findByHandle(user.handle())).contains(user);
      assertThat(users.passwordHash(user.handle())).contains("$2a$10$hash");
    }

    @Test
    @DisplayName("resolves a handle regardless of the case it is typed in")
    void handleIsCaseInsensitive() {
      UserItem user = newUser(uniqueHandle("Grace"));
      users.createWithHandle(user, "$2a$10$hash");

      // The stored key is lower-cased but the profile keeps the owner's capitalisation, so a
      // lookup has to normalise on the way in and must not normalise the value it returns.
      assertThat(users.findByHandle(user.handle().toUpperCase(java.util.Locale.ROOT)))
          .map(UserItem::handle)
          .contains(user.handle());
    }

    @Test
    @DisplayName("rejects a handle another account already holds")
    void rejectsDuplicateHandle() {
      String handle = uniqueHandle("dup");
      users.createWithHandle(newUser(handle), "$2a$10$hash");

      UserItem second = newUser(handle);
      assertThatThrownBy(() -> users.createWithHandle(second, "$2a$10$other"))
          .isInstanceOf(HandleAlreadyTakenException.class)
          .extracting(e -> ((HandleAlreadyTakenException) e).handle())
          .isEqualTo(handle);

      // The whole transaction is cancelled, so the loser must leave nothing behind. A user row
      // with no handle claim would be an account that exists and can never be found.
      assertThat(users.findById(second.userId())).isEmpty();
    }

    @Test
    @DisplayName("lets exactly one of many simultaneous registrations claim a handle")
    void concurrentRegistrationsProduceOneWinner() throws Exception {
      String handle = uniqueHandle("race");
      int attempts = 12;

      try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Callable<Boolean>> tasks =
            IntStream.range(0, attempts)
                .<Callable<Boolean>>mapToObj(
                    i ->
                        () -> {
                          try {
                            users.createWithHandle(newUser(handle), "$2a$10$hash");
                            return true;
                          } catch (HandleAlreadyTakenException e) {
                            return false;
                          }
                        })
                .toList();

        long winners =
            pool.invokeAll(tasks).stream()
                .map(
                    future -> {
                      try {
                        return future.get();
                      } catch (Exception e) {
                        throw new IllegalStateException(e);
                      }
                    })
                .filter(Boolean::booleanValue)
                .count();

        // This is the assertion the conditional write exists for. A read-then-write would pass
        // every other test in this class and fail here.
        assertThat(winners).isEqualTo(1);
      }
    }
  }

  @Nested
  @DisplayName("follower counting")
  class FollowerCounting {

    @Test
    @DisplayName("does not lose concurrent increments")
    void atomicIncrements() throws Exception {
      UserItem star = newUser(uniqueHandle("star"));
      users.createWithHandle(star, "$2a$10$hash");
      int increments = 200;

      try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Callable<Void>> tasks =
            IntStream.range(0, increments)
                .<Callable<Void>>mapToObj(
                    i ->
                        () -> {
                          users.adjustFollowerCount(star.userId(), 1);
                          return null;
                        })
                .toList();
        pool.invokeAll(tasks);
      }

      // A read-modify-write would land somewhere short of this and the shortfall would be
      // invisible -- a celebrity that never crosses the threshold and keeps being fanned out.
      assertThat(users.findById(star.userId()))
          .map(UserItem::followerCount)
          .contains((long) increments);
    }

    @Test
    @DisplayName("refuses to count followers for a user that does not exist")
    void requiresTheUserToExist() {
      // Without the attribute_exists condition, ADD creates the item. An unfollow arriving
      // after a deletion would resurrect the account as a row with nothing but a count.
      assertThatThrownBy(() -> users.adjustFollowerCount(Ids.newId().toString(), 1))
          .isInstanceOf(
              software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException.class);
    }
  }

  @Nested
  @DisplayName("celebrity flag")
  class CelebrityFlag {

    @Test
    @DisplayName("flips once and reports the loser of a concurrent flip")
    void singleWinner() {
      UserItem star = newUser(uniqueHandle("celeb"));
      users.createWithHandle(star, "$2a$10$hash");

      assertThat(users.setCelebrity(star.userId(), true)).isTrue();
      // The second call is what a concurrent request would issue. It must report false rather
      // than throw, because that is how the caller knows not to emit the transition twice.
      assertThat(users.setCelebrity(star.userId(), true)).isFalse();
      assertThat(users.findById(star.userId())).map(UserItem::celebrity).contains(true);

      assertThat(users.setCelebrity(star.userId(), false)).isTrue();
      assertThat(users.findById(star.userId())).map(UserItem::celebrity).contains(false);
    }
  }

  @Nested
  @DisplayName("follow graph")
  class Graph {

    @Test
    @DisplayName("creates an edge once and reports a repeat as a no-op")
    void followIsIdempotent() {
      String a = Ids.newId().toString();
      String b = Ids.newId().toString();

      assertThat(follows.follow(a, b)).isTrue();
      assertThat(follows.follow(a, b)).isFalse();
      assertThat(follows.isFollowing(a, b)).isTrue();
      // Direction matters: the edge is not symmetric and the reversed pair is a different key.
      assertThat(follows.isFollowing(b, a)).isFalse();
    }

    @Test
    @DisplayName("distinguishes a real unfollow from one with no edge behind it")
    void unfollowReportsWhetherItRemovedAnything() {
      String a = Ids.newId().toString();
      String b = Ids.newId().toString();

      assertThat(follows.unfollow(a, b)).isFalse();
      follows.follow(a, b);
      assertThat(follows.unfollow(a, b)).isTrue();
      assertThat(follows.isFollowing(a, b)).isFalse();
    }

    @Test
    @DisplayName("reads followers back from the KEYS_ONLY index")
    void followersComeFromTheReversedIndex() {
      String star = Ids.newId().toString();
      List<String> fans = IntStream.range(0, 5).mapToObj(i -> Ids.newId().toString()).toList();
      fans.forEach(fan -> follows.follow(fan, star));

      // The index projects KEYS_ONLY, so a row read through TableSchemas.FOLLOW would fail on
      // the missing createdAt. This passing is what proves the repository does not do that.
      FollowRepository.Page page = follows.followers(star, Optional.empty(), 50);

      assertThat(page.ids()).containsExactlyInAnyOrderElementsOf(fans);
    }

    @Test
    @DisplayName("pages through a follower list without repeating or skipping")
    void pagesFollowers() {
      String star = Ids.newId().toString();
      List<String> fans = IntStream.range(0, 7).mapToObj(i -> Ids.newId().toString()).toList();
      fans.forEach(fan -> follows.follow(fan, star));

      List<String> collected = new java.util.ArrayList<>();
      Optional<String> cursor = Optional.empty();
      int guard = 0;
      do {
        FollowRepository.Page page = follows.followers(star, cursor, 3);
        collected.addAll(page.ids());
        cursor = page.next();
      } while (cursor.isPresent() && ++guard < 10);

      // Fan-out walks this exact loop over a celebrity's followers. A cursor built from the
      // wrong attribute would either loop forever or silently drop a page of recipients.
      assertThat(collected).containsExactlyInAnyOrderElementsOf(fans).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("pages through a following list from the base table")
    void pagesFollowing() {
      String follower = Ids.newId().toString();
      List<String> followees =
          IntStream.range(0, 5).mapToObj(i -> Ids.newId().toString()).sorted().toList();
      followees.forEach(followee -> follows.follow(follower, followee));

      FollowRepository.Page first = follows.following(follower, Optional.empty(), 2);
      assertThat(first.ids()).hasSize(2);
      assertThat(first.next()).isPresent();

      FollowRepository.Page second = follows.following(follower, first.next(), 10);
      assertThat(second.ids()).doesNotContainAnyElementsOf(first.ids());
      assertThat(first.ids()).isSubsetOf(followees);
    }

    @Test
    @DisplayName("reports an exhausted partition rather than an endless cursor")
    void emptyPartition() {
      FollowRepository.Page page = follows.followers(Ids.newId().toString(), Optional.empty(), 10);

      assertThat(page.ids()).isEmpty();
      assertThat(page.next()).isEmpty();
    }
  }
}
