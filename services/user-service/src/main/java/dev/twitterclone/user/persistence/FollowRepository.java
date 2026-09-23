/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.persistence;

import dev.twitterclone.contracts.FollowItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Reads and writes the {@code follows} table.
 *
 * <p>The table is keyed follower-then-followee, which answers "who does X follow" as a single
 * partition query. The {@code followee-index} GSI reverses that pair to answer "who follows X",
 * which is the query fan-out runs on every tweet. That index projects {@code KEYS_ONLY}: fan-out
 * wants nothing but follower ids, and projecting more would multiply the write cost of every follow
 * by the size of an item nobody reads.
 */
@Repository
public class FollowRepository {

  private static final String FOLLOWER = "fwr";
  private static final String FOLLOWEE = "fwe";

  private final DynamoDbTable<FollowItem> follows;
  private final DynamoDbClient client;

  public FollowRepository(DynamoDbTable<FollowItem> followsTable, DynamoDbClient client) {
    this.follows = followsTable;
    this.client = client;
  }

  /**
   * Records a follow edge.
   *
   * @return {@code true} if this call created the edge, {@code false} if it already existed
   */
  public boolean follow(String followerId, String followeeId) {
    FollowItem edge =
        FollowItem.builder()
            .followerId(followerId)
            .followeeId(followeeId)
            .createdAt(Instant.now())
            .build();
    try {
      follows.putItem(
          PutItemEnhancedRequest.builder(FollowItem.class)
              .item(edge)
              // Without this condition a repeated follow -- a double-tapped button, a retried
              // request -- overwrites the edge and is indistinguishable from a new one, so the
              // caller would increment the follower count twice. This condition is what makes
              // maintaining that count outside a transaction safe.
              .conditionExpression(
                  Expression.builder().expression("attribute_not_exists(fwr)").build())
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  /**
   * Removes a follow edge.
   *
   * @return {@code true} if an edge was removed, {@code false} if there was nothing to remove
   */
  public boolean unfollow(String followerId, String followeeId) {
    FollowItem removed =
        follows.deleteItem(Key.builder().partitionValue(followerId).sortValue(followeeId).build());
    // deleteItem returns the previous item, or null if the key was absent. That is what
    // separates unfollowing someone the caller never followed from a real unfollow, so the
    // follower count is only decremented for an edge that actually existed.
    return removed != null;
  }

  public boolean isFollowing(String followerId, String followeeId) {
    return follows.getItem(Key.builder().partitionValue(followerId).sortValue(followeeId).build())
        != null;
  }

  /**
   * One page of the accounts {@code followerId} follows.
   *
   * @param after the followee id the previous page ended on, or empty for the first page
   */
  public Page following(String followerId, Optional<String> after, int limit) {
    return query(null, FOLLOWER, followerId, FOLLOWEE, after, limit);
  }

  /**
   * One page of the accounts following {@code followeeId}.
   *
   * <p>Fan-out walks this in pages rather than collecting every follower: a celebrity's follower
   * list does not fit in a worker's heap, and materialising it would trade a slow fan-out for an
   * OOM kill.
   */
  public Page followers(String followeeId, Optional<String> after, int limit) {
    return query(FollowItem.FOLLOWEE_INDEX, FOLLOWEE, followeeId, FOLLOWER, after, limit);
  }

  /**
   * Runs one page of a follow-graph query and returns the ids on the far side of each edge.
   *
   * <p>Deliberately the low-level client rather than the enhanced one. {@link FollowItem} is a
   * faithful description of a <em>table</em> row and its builder rejects a missing {@code
   * createdAt}, but {@code followee-index} is {@code KEYS_ONLY} and does not carry that attribute.
   * Mapping a projected row through the table schema would therefore throw on every follower page.
   * Pulling the two key attributes out by hand keeps the projection honest, and is all either
   * caller wanted anyway.
   *
   * @param index the GSI to read, or {@code null} for the base table
   * @param partitionKey name of the partition key in whichever of the two is being read
   * @param partitionValue the user whose edges are wanted
   * @param projected name of the sort key, which is also the id being returned
   */
  private Page query(
      String index,
      String partitionKey,
      String partitionValue,
      String projected,
      Optional<String> after,
      int limit) {

    QueryRequest.Builder request =
        QueryRequest.builder()
            .tableName(follows.tableName())
            .indexName(index)
            .keyConditionExpression("#pk = :pk")
            .expressionAttributeNames(Map.of("#pk", partitionKey))
            .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(partitionValue)))
            .limit(limit);

    after.ifPresent(
        cursor ->
            request.exclusiveStartKey(
                // A GSI cursor must carry the base table's key as well as the index's, because
                // the index is not unique on its own pair. Here the two pairs are the same two
                // attributes in the opposite order, so this map satisfies both.
                Map.of(
                    partitionKey, AttributeValue.fromS(partitionValue),
                    projected, AttributeValue.fromS(cursor))));

    QueryResponse response = client.query(request.build());
    List<String> ids = response.items().stream().map(item -> item.get(projected).s()).toList();

    // An empty lastEvaluatedKey means the partition is exhausted. A non-empty one means only
    // that this page filled, not that another page has anything in it.
    Optional<String> next =
        response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
            ? Optional.of(response.lastEvaluatedKey().get(projected).s())
            : Optional.empty();

    return new Page(ids, next);
  }

  /**
   * A page of user ids and the cursor that continues it.
   *
   * @param ids the user ids on this page
   * @param next the cursor for the following page, empty when the partition is exhausted
   */
  public record Page(List<String> ids, Optional<String> next) {}
}
