/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.persistence;

import dev.starling.contracts.LikeItem;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Reads and writes the {@code likes} table.
 *
 * <p>The row's existence is the entire fact being stored. Both methods return whether they changed
 * anything, and that return value is what the caller uses to decide whether to move the counter on
 * the tweet — a like that was already there must not increment it again.
 */
@Repository
public class LikeRepository {

  /** DynamoDB's hard limit on keys in one {@code BatchGetItem}. */
  private static final int MAX_BATCH = 100;

  private final DynamoDbTable<LikeItem> likes;
  private final DynamoDbEnhancedClient enhanced;

  public LikeRepository(DynamoDbTable<LikeItem> likesTable, DynamoDbEnhancedClient enhanced) {
    this.likes = likesTable;
    this.enhanced = enhanced;
  }

  /**
   * Records a like.
   *
   * @param tweetId the tweet
   * @param userId who liked it
   * @return {@code true} if this call created the row, {@code false} if it was already there
   */
  public boolean like(String tweetId, String userId) {
    try {
      likes.putItem(
          PutItemEnhancedRequest.builder(LikeItem.class)
              .item(
                  LikeItem.builder().tweetId(tweetId).userId(userId).likedAt(Instant.now()).build())
              // Without this, a double-tapped heart overwrites the row and looks exactly like
              // a first like, so the counter moves twice for one user. The conditional put is
              // what makes maintaining that counter outside a transaction correct.
              .conditionExpression(
                  Expression.builder().expression("attribute_not_exists(tid)").build())
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  /**
   * Removes a like.
   *
   * @param tweetId the tweet
   * @param userId who is unliking it
   * @return {@code true} if a row was removed, {@code false} if there was nothing to remove
   */
  public boolean unlike(String tweetId, String userId) {
    // deleteItem returns the previous item, or null when the key was absent. That is the only
    // way to learn whether anything was actually deleted without a preceding read.
    LikeItem removed =
        likes.deleteItem(Key.builder().partitionValue(tweetId).sortValue(userId).build());
    return removed != null;
  }

  /**
   * Whether a user has liked a tweet.
   *
   * @param tweetId the tweet
   * @param userId the user
   * @return true if the row exists
   */
  public boolean hasLiked(String tweetId, String userId) {
    return likes.getItem(Key.builder().partitionValue(tweetId).sortValue(userId).build()) != null;
  }

  /**
   * Which of these tweets one user has liked.
   *
   * <p>A page of tweets needs this for every row, and asking {@link #hasLiked} once per row is the
   * classic N+1: twenty sequential round trips to render one screen, each one a chance to be slow.
   * One {@code BatchGetItem} is a single request no matter the page size.
   *
   * <p>Returns the subset that was liked rather than a map over the input, because absence is the
   * answer for everything else and a {@code Set} cannot be misread as "unknown".
   *
   * @param tweetIds the tweets on the page
   * @param userId the caller
   * @return the ids the caller has liked, possibly empty, never null
   */
  public Set<String> likedAmong(Collection<String> tweetIds, String userId) {
    List<String> distinct = tweetIds.stream().distinct().toList();
    if (distinct.isEmpty()) {
      return Set.of();
    }
    if (distinct.size() > MAX_BATCH) {
      throw new IllegalArgumentException(
          "BatchGetItem accepts at most " + MAX_BATCH + " keys, got " + distinct.size());
    }

    ReadBatch.Builder<LikeItem> batch =
        ReadBatch.builder(LikeItem.class).mappedTableResource(likes);
    distinct.forEach(
        id -> batch.addGetItem(Key.builder().partitionValue(id).sortValue(userId).build()));

    return enhanced
        .batchGetItem(BatchGetItemEnhancedRequest.builder().readBatches(batch.build()).build())
        .resultsForTable(likes)
        .stream()
        .map(LikeItem::tweetId)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * When a user liked a tweet, if they did.
   *
   * @param tweetId the tweet
   * @param userId the user
   * @return the moment of the like, or empty
   */
  public Optional<Instant> likedAt(String tweetId, String userId) {
    return Optional.ofNullable(
            likes.getItem(Key.builder().partitionValue(tweetId).sortValue(userId).build()))
        .map(LikeItem::likedAt);
  }
}
