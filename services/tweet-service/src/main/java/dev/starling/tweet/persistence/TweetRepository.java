/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.persistence;

import dev.starling.contracts.TweetItem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchGetItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * Reads and writes the {@code tweets} table.
 *
 * <p>This table is the system's event source: its stream is what fan-out and the search indexer
 * consume (ADR-0012). Every write here is therefore also a published event, which is why nothing in
 * this class rewrites a stored tweet to correct it — an idempotent-looking overwrite emits a second
 * stream record, and the same post gets fanned out twice.
 *
 * <p>Unlike the follow graph, this one can use the enhanced client throughout: {@code author-index}
 * projects {@code ALL}, so a row read from the index is a complete item and maps cleanly through
 * the table schema.
 */
@Repository
public class TweetRepository {

  /** Largest batch DynamoDB will accept in one {@code BatchGetItem}. */
  public static final int MAX_BATCH = 100;

  private static final String TWEET_ID = "tid";
  private static final String AUTHOR_ID = "aid";
  private static final String LIKE_COUNT = "lc";

  private final DynamoDbTable<TweetItem> tweets;
  private final DynamoDbIndex<TweetItem> authorIndex;
  private final DynamoDbEnhancedClient enhanced;
  private final DynamoDbClient client;

  public TweetRepository(
      DynamoDbTable<TweetItem> tweetsTable,
      DynamoDbEnhancedClient enhanced,
      DynamoDbClient client) {
    this.tweets = tweetsTable;
    this.authorIndex = tweetsTable.index(TweetItem.AUTHOR_INDEX);
    this.enhanced = enhanced;
    this.client = client;
  }

  /**
   * Stores a new tweet.
   *
   * @param tweet the tweet to store
   * @return {@code true} if it was stored, {@code false} if that id already existed
   */
  public boolean create(TweetItem tweet) {
    try {
      tweets.putItem(
          PutItemEnhancedRequest.builder(TweetItem.class)
              .item(tweet)
              // A UUIDv7 collision is not what this guards. A retry that slipped past the
              // idempotency check is, and so is a bug that reuses an id: without the
              // condition either one silently replaces a stored tweet and emits a second
              // stream record, so the same post is fanned out again.
              .conditionExpression(
                  Expression.builder().expression("attribute_not_exists(tid)").build())
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  /**
   * Looks up one tweet by id.
   *
   * @param tweetId the tweet
   * @return the tweet, or empty if there is none
   */
  public Optional<TweetItem> findById(String tweetId) {
    return Optional.ofNullable(tweets.getItem(Key.builder().partitionValue(tweetId).build()));
  }

  /**
   * Looks up many tweets at once, which is how a timeline is rendered.
   *
   * <p>Returned as a map rather than a list because {@code BatchGetItem} neither preserves request
   * order nor promises to return everything asked for. A caller that assumed position would be
   * correct right up until the first deleted tweet — and then it would render the right text under
   * the wrong author, which is worse than rendering nothing.
   *
   * @param tweetIds the ids to fetch, at most {@value #MAX_BATCH} of them
   * @return the tweets that exist, keyed by id
   */
  public Map<String, TweetItem> findAllById(List<String> tweetIds) {
    List<String> distinct = tweetIds.stream().distinct().toList();
    if (distinct.isEmpty()) {
      return Map.of();
    }
    if (distinct.size() > MAX_BATCH) {
      // DynamoDB rejects a larger batch outright. Failing here names the caller's mistake
      // rather than surfacing a ValidationException from three frames down.
      throw new IllegalArgumentException(
          "BatchGetItem accepts at most " + MAX_BATCH + " keys, got " + distinct.size());
    }

    ReadBatch.Builder<TweetItem> batch =
        ReadBatch.builder(TweetItem.class).mappedTableResource(tweets);
    distinct.forEach(id -> batch.addGetItem(Key.builder().partitionValue(id).build()));

    return enhanced
        .batchGetItem(BatchGetItemEnhancedRequest.builder().readBatches(batch.build()).build())
        .resultsForTable(tweets)
        .stream()
        .collect(Collectors.toMap(TweetItem::tweetId, Function.identity()));
  }

  /**
   * One page of an author's own tweets, newest first.
   *
   * <p>Reads {@code author-index}, which is eventually consistent — a just-posted tweet can be
   * absent from its author's own profile for a moment. That is the trade the key design makes
   * deliberately: a permalink has to be a strongly consistent point read, and it cannot be if the
   * partition key is the author.
   *
   * @param authorId whose tweets
   * @param after cursor from the previous page, absent for the first
   * @param limit page size
   * @return the page and its continuation cursor
   */
  public TweetPage byAuthor(String authorId, Optional<String> after, int limit) {
    QueryEnhancedRequest.Builder request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(authorId).build()))
            // Descending. Tweet ids are UUIDv7, so reverse lexicographic order is newest-first
            // without storing, indexing or sorting on a timestamp at all.
            .scanIndexForward(false)
            .limit(limit);

    after.ifPresent(
        cursor ->
            request.exclusiveStartKey(
                // A GSI cursor must carry the base table's key as well as the index's. The
                // index is keyed (aid, tid) and the base table (tid), so these two cover both.
                Map.of(
                    AUTHOR_ID, AttributeValue.fromS(authorId),
                    TWEET_ID, AttributeValue.fromS(cursor))));

    // One page, not the whole iterator. The SDK's paginator would happily walk an entire
    // author history; taking the first page is what leaves the cursor for the caller to advance.
    Optional<Page<TweetItem>> page = authorIndex.query(request.build()).stream().findFirst();
    if (page.isEmpty()) {
      return new TweetPage(List.of(), Optional.empty());
    }

    Map<String, AttributeValue> last = page.get().lastEvaluatedKey();
    return new TweetPage(
        page.get().items(),
        last == null || last.isEmpty() ? Optional.empty() : Optional.of(last.get(TWEET_ID).s()));
  }

  /**
   * Moves a tweet's like counter.
   *
   * <p>An atomic {@code ADD} rather than a read-modify-write, for the same reason the follower
   * count is one: likes arrive concurrently, and most heavily on exactly the tweets where losing
   * one would be noticed.
   *
   * @param tweetId the tweet
   * @param delta {@code +1} or {@code -1}
   * @return the new like count, or empty if the tweet no longer exists
   */
  public Optional<Long> adjustLikeCount(String tweetId, long delta) {
    try {
      var response =
          client.updateItem(
              UpdateItemRequest.builder()
                  .tableName(tweets.tableName())
                  .key(Map.of(TWEET_ID, AttributeValue.fromS(tweetId)))
                  .updateExpression("ADD #lc :d")
                  .conditionExpression("attribute_exists(tid)")
                  .expressionAttributeNames(Map.of("#lc", LIKE_COUNT))
                  .expressionAttributeValues(
                      Map.of(":d", AttributeValue.fromN(Long.toString(delta))))
                  .returnValues(ReturnValue.UPDATED_NEW)
                  .build());
      return Optional.of(Long.parseLong(response.attributes().get(LIKE_COUNT).n()));
    } catch (ConditionalCheckFailedException e) {
      // The tweet was deleted between the like row being written and the counter moving.
      // Without the condition, ADD would create a stub item holding nothing but a number,
      // and the stream would publish it as a tweet with no author and no text.
      return Optional.empty();
    }
  }

  /**
   * Deletes a tweet, if the caller wrote it.
   *
   * @param tweetId the tweet to delete
   * @param authorId who is asking
   * @return the deleted tweet, or empty if there was nothing to delete
   * @throws NotTheAuthorException if the tweet exists and belongs to somebody else
   */
  public Optional<TweetItem> delete(String tweetId, String authorId) {
    try {
      var response =
          client.deleteItem(
              DeleteItemRequest.builder()
                  .tableName(tweets.tableName())
                  .key(Map.of(TWEET_ID, AttributeValue.fromS(tweetId)))
                  // Ownership is the condition, not a read followed by a delete. Checking
                  // first leaves a window in which the tweet is deleted and the id reused,
                  // and the missing-item arm is what keeps a delete of nothing from being
                  // reported as a delete of somebody else's post.
                  .conditionExpression("attribute_not_exists(tid) OR aid = :aid")
                  .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS(authorId)))
                  // The old image is the return value, so the two success cases -- deleted,
                  // and there was nothing there -- are told apart without a second read.
                  .returnValues(ReturnValue.ALL_OLD)
                  .build());
      Map<String, AttributeValue> old = response.attributes();
      return old == null || old.isEmpty()
          ? Optional.empty()
          : Optional.of(tweets.tableSchema().mapToItem(old));
    } catch (ConditionalCheckFailedException e) {
      throw new NotTheAuthorException(tweetId);
    }
  }

  /**
   * A page of tweets and the cursor that continues it.
   *
   * @param tweets the tweets on this page
   * @param next the cursor for the following page, empty when the history is exhausted
   */
  public record TweetPage(List<TweetItem> tweets, Optional<String> next) {

    public TweetPage {
      tweets = List.copyOf(tweets);
    }
  }

  /** Raised when a caller tries to modify a tweet they did not write. */
  public static class NotTheAuthorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotTheAuthorException(String tweetId) {
      super("Tweet " + tweetId + " belongs to another author");
    }
  }
}
