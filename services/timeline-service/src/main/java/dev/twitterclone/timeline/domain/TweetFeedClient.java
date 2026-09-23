/* SPDX-License-Identifier: MIT */
package dev.twitterclone.timeline.domain;

import dev.twitterclone.timeline.config.TimelineProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.type.TypeReference;

/**
 * Reads tweet bodies from tweet-service, across both cache tiers.
 *
 * <p>This class is where the two-tier split earns its keep, and the two methods use the tiers for
 * opposite reasons.
 *
 * <p>{@link #recentByAuthor} caches a celebrity's recent tweets in the celebrity tier. One entry
 * serves every follower of that account — a million timelines can be rendered from a single cached
 * list, and that ratio is the entire justification for not fanning their tweets out in the first
 * place.
 *
 * <p>{@link #hydrate} caches individual tweet bodies in the main tier. These are read by roughly
 * one person each, are individually cheap to refetch, and are exactly the kind of long tail that
 * LRU handles well and that would otherwise evict the celebrity entries.
 */
@Component
public class TweetFeedClient {

  private static final Logger LOG = LoggerFactory.getLogger(TweetFeedClient.class);

  /** tweet-service's own ceiling on a batch read. */
  private static final int MAX_BATCH = 100;

  private final RestClient tweets;
  private final JsonCache celebCache;
  private final JsonCache mainCache;
  private final TimelineProperties properties;

  public TweetFeedClient(
      @Qualifier("tweetServiceClient") RestClient tweetServiceClient,
      @Qualifier("celebCache") JsonCache celebCache,
      @Qualifier("mainCache") JsonCache mainCache,
      TimelineProperties properties) {
    this.tweets = tweetServiceClient;
    this.celebCache = celebCache;
    this.mainCache = mainCache;
    this.properties = properties;
  }

  /**
   * A celebrity's most recent tweets.
   *
   * <p>Cached without reference to who is asking. Keying this by follower would defeat it
   * completely: the value of the entry is precisely that it is shared.
   *
   * @param authorId the celebrity
   * @return their recent tweets, newest first, empty if tweet-service is unreachable
   */
  public List<TweetView> recentByAuthor(String authorId) {
    return celebCache.get(
        "celeb:feed:" + authorId,
        new TypeReference<List<TweetView>>() {},
        properties.celebrityFeedTtl(),
        () -> fetchByAuthor(authorId));
  }

  /**
   * Fetches tweet bodies by id, cache first.
   *
   * <p>Missing ids are simply absent from the result. A tweet deleted after it was fanned out
   * leaves its timeline row behind — fan-out is not transactional with deletion and could not be
   * without making every delete an O(followers) write — so the read path has to treat an id with no
   * body as an entry to drop rather than an error.
   *
   * @param ids the tweets to read, in any order
   * @return those that exist, keyed by id
   */
  public Map<String, TweetView> hydrate(List<String> ids) {
    Map<String, TweetView> found = new LinkedHashMap<>();
    List<String> misses = new ArrayList<>();
    for (String id : ids) {
      Optional<TweetView> hit = mainCache.read(key(id), new TypeReference<TweetView>() {});
      if (hit.isPresent()) {
        found.put(id, hit.get());
      } else {
        misses.add(id);
      }
    }
    for (int from = 0; from < misses.size(); from += MAX_BATCH) {
      Map<String, TweetView> fetched =
          fetchByIds(misses.subList(from, Math.min(from + MAX_BATCH, misses.size())));
      fetched.forEach(
          (id, tweet) -> {
            mainCache.put(key(id), properties.tweetTtl(), tweet);
            found.put(id, tweet);
          });
    }
    return found;
  }

  private List<TweetView> fetchByAuthor(String authorId) {
    try {
      TweetPage page =
          tweets
              .get()
              .uri(
                  builder ->
                      builder
                          .path("/v1/tweets/by-author/{id}")
                          .queryParam("limit", properties.celebrityFeedSize())
                          .build(authorId))
              .retrieve()
              .body(TweetPage.class);
      return page == null ? List.of() : page.items();
    } catch (RestClientException e) {
      LOG.warn("celebrity feed unavailable for {}, merging without it", authorId, e);
      return List.of();
    }
  }

  private Map<String, TweetView> fetchByIds(List<String> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    try {
      TweetBatch batch =
          tweets
              .get()
              .uri(builder -> builder.path("/v1/tweets").queryParam("ids", ids).build())
              .retrieve()
              .body(TweetBatch.class);
      return batch == null ? Map.of() : batch.items();
    } catch (RestClientException e) {
      LOG.warn("tweet hydration failed for {} ids", ids.size(), e);
      return Map.of();
    }
  }

  private static String key(String tweetId) {
    return "tweet:" + tweetId;
  }

  private record TweetPage(List<TweetView> items, @Nullable String nextCursor) {}

  private record TweetBatch(Map<String, TweetView> items) {}
}
