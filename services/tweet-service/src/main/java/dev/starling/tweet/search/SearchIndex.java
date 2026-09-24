/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.search;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The search index, in Postgres.
 *
 * <p>{@link JdbcClient} rather than JPA. Every query worth writing here uses {@code @@}, {@code
 * websearch_to_tsquery} or a keyset comparison on a row constructor, none of which have a portable
 * JPA expression — so the mapping would exist only to be bypassed by native queries, while still
 * costing a Hibernate bootstrap and a dirty-checking session for rows that are never mutated in
 * place.
 *
 * <p>Rows are keyed by tweet id and written with {@code ON CONFLICT DO UPDATE}, which makes
 * indexing idempotent. That is what lets the stream consumer checkpoint after a batch and replay it
 * on restart: a replayed insert rewrites an identical row.
 */
@Repository
public class SearchIndex {

  /**
   * How many rows a keyset page may ask for. A caller-supplied limit is clamped to this rather than
   * rejected, because the ceiling exists to protect the database, not to be part of the contract.
   */
  public static final int MAX_LIMIT = 50;

  private static final String UPSERT =
      """
      INSERT INTO search.tweet (tweet_id, author_id, body, created_at)
      VALUES (:tweetId, :authorId, :body, :createdAt)
      ON CONFLICT (tweet_id) DO UPDATE
        SET author_id = EXCLUDED.author_id,
            body = EXCLUDED.body,
            created_at = EXCLUDED.created_at
      """;

  // websearch_to_tsquery, not plainto_tsquery or to_tsquery. to_tsquery throws on any input a
  // user might plausibly type -- an unbalanced quote is a 500 -- and plainto_tsquery ANDs every
  // word and understands no operators. websearch_to_tsquery parses what people already type
  // into search boxes (quoted phrases, OR, leading -) and cannot be made to throw.
  private static final String SEARCH =
      """
      SELECT tweet_id, author_id, body, created_at
      FROM search.tweet
      WHERE document @@ websearch_to_tsquery('english', :query)
        AND (:cursorAt::timestamptz IS NULL
             OR (created_at, tweet_id) < (:cursorAt::timestamptz, :cursorId))
      ORDER BY created_at DESC, tweet_id DESC
      LIMIT :limit
      """;

  private final JdbcClient jdbc;

  public SearchIndex(JdbcClient jdbcClient) {
    this.jdbc = jdbcClient;
  }

  /**
   * Adds or replaces a tweet in the index.
   *
   * @param tweetId the tweet id
   * @param authorId who wrote it
   * @param body the text to index
   * @param createdAt when it was posted
   */
  public void index(String tweetId, String authorId, String body, Instant createdAt) {
    jdbc.sql(UPSERT)
        .param("tweetId", tweetId)
        .param("authorId", authorId)
        .param("body", body)
        .param("createdAt", java.sql.Timestamp.from(createdAt))
        .update();
  }

  /**
   * Removes a tweet from the index.
   *
   * <p>Silent when the row is absent. A delete arriving for a tweet that was never indexed is the
   * normal outcome of a replay, not an error.
   *
   * @param tweetId the tweet id
   * @return whether a row was removed
   */
  public boolean remove(String tweetId) {
    return jdbc.sql("DELETE FROM search.tweet WHERE tweet_id = :tweetId")
            .param("tweetId", tweetId)
            .update()
        > 0;
  }

  /**
   * Matching tweets, newest first.
   *
   * <p>Ordered by recency rather than {@code ts_rank}. Relevance ordering cannot be paged by keyset
   * — rank is not unique and not monotonic — so it would force {@code OFFSET}, which re-scans and
   * re-sorts the whole match set for every page and shifts rows under a reader as new tweets
   * arrive. Recency is both pageable and what a timeline product's users expect from a search box.
   *
   * @param query raw user input, parsed as a web search expression
   * @param limit page size, clamped to {@link #MAX_LIMIT}
   * @param cursor where to resume, or null for the first page
   * @return the matching rows
   */
  public List<Hit> search(String query, int limit, @Nullable Cursor cursor) {
    return jdbc.sql(SEARCH)
        .param("query", query)
        .param("cursorAt", cursor == null ? null : java.sql.Timestamp.from(cursor.createdAt()))
        .param("cursorId", cursor == null ? null : cursor.tweetId())
        .param("limit", Math.clamp(limit, 1, MAX_LIMIT))
        .query(
            (rs, row) ->
                new Hit(
                    rs.getString("tweet_id"),
                    rs.getString("author_id"),
                    rs.getString("body"),
                    rs.getTimestamp("created_at").toInstant()))
        .list();
  }

  /**
   * One indexed tweet.
   *
   * @param tweetId the tweet id
   * @param authorId who wrote it
   * @param body the indexed text
   * @param createdAt when it was posted
   */
  public record Hit(String tweetId, String authorId, String body, Instant createdAt) {}

  /**
   * A position in a result set.
   *
   * <p>Both columns, not just the timestamp. Two tweets can share a millisecond, and a cursor on
   * time alone would either skip one of them or return it twice.
   *
   * @param createdAt the last row's timestamp
   * @param tweetId the last row's id
   */
  public record Cursor(Instant createdAt, String tweetId) {}
}
