/* SPDX-License-Identifier: MIT */
package dev.starling.fanout.persistence;

import dev.starling.platform.aws.DynamoDbProperties;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * Enumerates an author's followers, the recipients of a fan-out.
 *
 * <p>Uses the low-level client rather than the enhanced one. {@code followee-index} projects {@code
 * KEYS_ONLY}, and the enhanced client insists on materialising a full item from a query — it would
 * hand back records with every non-key attribute null, which is indistinguishable from data loss at
 * the call site. Reading raw attribute maps makes the projection's shape visible in the code.
 *
 * <p>{@code KEYS_ONLY} is not an oversight. This is the largest index in the system, and fan-out
 * needs follower ids and nothing else; projecting {@code ALL} would duplicate the entire follow
 * graph and double its write cost for data no consumer reads.
 */
@Repository
public class FollowerRepository {

  private static final String FOLLOWER = "fwr";
  private static final String FOLLOWEE = "fwe";
  private static final String INDEX = "followee-index";

  private final DynamoDbClient client;
  private final String tableName;

  public FollowerRepository(DynamoDbClient client, DynamoDbProperties properties) {
    this.client = client;
    this.tableName = properties.table("follows");
  }

  /**
   * One page of an author's followers.
   *
   * @param authorId whose followers
   * @param after exclusive start, the last follower id of the previous page
   * @param limit page size
   * @return the page
   */
  public Page followers(String authorId, Optional<String> after, int limit) {
    QueryRequest.Builder request =
        QueryRequest.builder()
            .tableName(tableName)
            .indexName(INDEX)
            .keyConditionExpression("#fwe = :author")
            .expressionAttributeNames(Map.of("#fwe", FOLLOWEE))
            .expressionAttributeValues(Map.of(":author", AttributeValue.fromS(authorId)))
            .limit(limit);

    after.ifPresent(
        cursor ->
            // A GSI cursor carries the index's key *and* the base table's, because a position
            // in the index is only unique once both are given.
            request.exclusiveStartKey(
                Map.of(
                    FOLLOWEE, AttributeValue.fromS(authorId),
                    FOLLOWER, AttributeValue.fromS(cursor))));

    QueryResponse response = client.query(request.build());
    List<String> ids = response.items().stream().map(item -> item.get(FOLLOWER).s()).toList();
    Map<String, AttributeValue> last = response.lastEvaluatedKey();
    return new Page(
        ids,
        last == null || last.isEmpty() ? Optional.empty() : Optional.of(last.get(FOLLOWER).s()));
  }

  /**
   * One page of follower ids.
   *
   * @param ids the followers
   * @param next cursor for the next page, absent at the end
   */
  public record Page(List<String> ids, Optional<String> next) {}
}
