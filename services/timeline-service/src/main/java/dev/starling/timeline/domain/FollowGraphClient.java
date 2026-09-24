/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.domain;

import dev.starling.timeline.config.TimelineProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.type.TypeReference;

/**
 * Asks user-service which of a user's followees are celebrities.
 *
 * <p>The answer is cached in the <em>celebrity</em> tier, not the main one, even though the key is
 * per-user. The reason is the eviction policy rather than the key shape: this list is what tells
 * the read path whose tweets were deliberately never fanned out, so losing it does not cost a cache
 * miss, it silently produces an incomplete timeline until user-service answers again. {@code
 * volatile-ttl} keeps it alive under memory pressure in a way {@code allkeys-lru} would not.
 */
@Component
public class FollowGraphClient {

  private static final Logger LOG = LoggerFactory.getLogger(FollowGraphClient.class);

  private final RestClient users;
  private final JsonCache cache;
  private final TimelineProperties properties;

  public FollowGraphClient(
      @Qualifier("userServiceClient") RestClient userServiceClient,
      @Qualifier("celebCache") JsonCache celebCache,
      TimelineProperties properties) {
    this.users = userServiceClient;
    this.cache = celebCache;
    this.properties = properties;
  }

  /**
   * The celebrity accounts a user follows.
   *
   * <p>Returns an empty list when user-service is unreachable rather than propagating. A timeline
   * missing its celebrity tweets is visibly incomplete but still useful; a timeline that 500s
   * because a dependency is slow is not. The materialised half of the merge is unaffected.
   *
   * @param userId whose following list to inspect
   * @return the celebrity followees, possibly empty
   */
  public List<String> celebrityFollowees(String userId) {
    return cache.get(
        "celeb:followees:" + userId,
        new TypeReference<List<String>>() {},
        properties.followeesTtl(),
        () -> fetch(userId));
  }

  private List<String> fetch(String userId) {
    try {
      Page page =
          users
              .get()
              .uri("/v1/users/{id}/following/celebrities", userId)
              .retrieve()
              .body(Page.class);
      return page == null ? List.of() : page.items();
    } catch (RestClientException e) {
      LOG.warn("celebrity followees unavailable for {}, merging without them", userId, e);
      return List.of();
    }
  }

  /** user-service's page shape, narrowed to the field this client needs. */
  private record Page(List<String> items, @Nullable String nextCursor) {}
}
