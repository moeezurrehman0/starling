/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.persistence;

import dev.twitterclone.contracts.LikeItem;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
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

  private final DynamoDbTable<LikeItem> likes;

  public LikeRepository(DynamoDbTable<LikeItem> likesTable) {
    this.likes = likesTable;
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
