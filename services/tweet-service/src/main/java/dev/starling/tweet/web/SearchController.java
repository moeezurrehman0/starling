/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.web;

import dev.starling.contracts.TweetItem;
import dev.starling.tweet.domain.TweetService;
import dev.starling.tweet.search.SearchIndex;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Full-text search over tweets.
 *
 * <p>Postgres answers <em>which</em> tweets match; DynamoDB answers <em>what they are</em>. The
 * index is derived data and lags the write path, so rendering from it directly would show deleted
 * tweets and stale like counts — a search result that disagrees with the tweet it links to. Looking
 * the ids back up costs one {@code BatchGetItem} per page and makes a deleted tweet simply fall out
 * of the results, with no delete path to keep in step.
 *
 * <p>Anonymous. Search is a read of public tweets, and requiring a token here would make the most
 * common first action a new visitor takes the one thing they cannot do.
 */
@RestController
@RequestMapping("/v1")
public class SearchController {

  private static final int DEFAULT_LIMIT = 20;

  private final SearchIndex index;
  private final TweetService tweets;

  public SearchController(SearchIndex index, TweetService tweets) {
    this.index = index;
    this.tweets = tweets;
  }

  /**
   * Tweets matching a query, newest first.
   *
   * @param q the search expression, as typed
   * @param cursor cursor from the previous page
   * @param limit page size, clamped
   * @return the page
   */
  @GetMapping("/search/tweets")
  public Api.TweetPage search(
      @RequestParam String q,
      @RequestParam(required = false) @Nullable String cursor,
      @RequestParam(defaultValue = "20") int limit) {

    // An empty query returns nothing rather than everything. websearch_to_tsquery('') matches
    // no rows, so this is only a short-circuit -- but it is the difference between a blank
    // search box costing nothing and costing a full index scan.
    if (q.isBlank()) {
      return new Api.TweetPage(List.of(), null);
    }

    int size = Math.clamp(limit, 1, SearchIndex.MAX_LIMIT);
    List<SearchIndex.Hit> hits = index.search(q, size, decode(cursor));
    if (hits.isEmpty()) {
      return new Api.TweetPage(List.of(), null);
    }

    Map<String, TweetItem> found =
        tweets.byIds(hits.stream().map(SearchIndex.Hit::tweetId).toList());
    List<Api.Tweet> items =
        hits.stream()
            .map(hit -> found.get(hit.tweetId()))
            .filter(java.util.Objects::nonNull)
            .map(item -> Api.Tweet.of(item, null))
            .toList();

    // The cursor comes from the last indexed hit, not the last rendered tweet. If every hit on
    // this page has since been deleted the page renders empty, and a cursor derived from the
    // rendered list would be null -- ending pagination early and hiding everything behind it.
    SearchIndex.Hit last = hits.get(hits.size() - 1);
    String next = hits.size() < size ? null : encode(last);
    return new Api.TweetPage(items, next);
  }

  private static String encode(SearchIndex.Hit hit) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            (hit.createdAt() + "|" + hit.tweetId())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static SearchIndex.@Nullable Cursor decode(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String decoded =
          new String(
              Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
      int separator = decoded.indexOf('|');
      if (separator < 0) {
        return null;
      }
      return new SearchIndex.Cursor(
          Instant.parse(decoded.substring(0, separator)), decoded.substring(separator + 1));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      // A cursor is opaque to the client, so a malformed one is either corruption or a probe.
      // Starting from the beginning is harmless; a 400 would turn a stale bookmark into an
      // error page.
      return null;
    }
  }

  /**
   * Exposed for tests that need to assert the cursor round-trips.
   *
   * @param cursor the encoded cursor
   * @return the decoded position, if it parses
   */
  static Optional<SearchIndex.Cursor> parse(String cursor) {
    return Optional.ofNullable(decode(cursor));
  }
}
