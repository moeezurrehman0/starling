/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.starling.timeline.config.TimelineProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("FollowGraphClient")
class FollowGraphClientTest {

  private static final TimelineProperties PROPERTIES =
      new TimelineProperties(
          "http://users",
          "http://tweets",
          50,
          Duration.ofSeconds(30),
          Duration.ofMinutes(5),
          Duration.ofSeconds(60));

  private RestClient.Builder builder;
  private MockRestServiceServer server;
  private RecordingCache cache;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl("http://users");
    server = MockRestServiceServer.bindTo(builder).build();
    cache = new RecordingCache("celeb");
  }

  private FollowGraphClient client() {
    return new FollowGraphClient(builder.build(), cache, PROPERTIES);
  }

  @Test
  @DisplayName("reads the celebrity followees from user-service")
  void fetches() {
    server
        .expect(requestTo("http://users/v1/users/me/following/celebrities"))
        .andRespond(
            withSuccess(
                "{\"items\":[\"star-1\",\"star-2\"],\"nextCursor\":null}",
                MediaType.APPLICATION_JSON));

    assertThat(client().celebrityFollowees("me")).containsExactly("star-1", "star-2");
    server.verify();
  }

  @Test
  @DisplayName("caches in the celebrity tier, not the main one")
  void cachesInTheCelebrityTier() {
    server
        .expect(requestTo("http://users/v1/users/me/following/celebrities"))
        .andRespond(withSuccess("{\"items\":[\"star-1\"]}", MediaType.APPLICATION_JSON));

    client().celebrityFollowees("me");

    // The tier choice is about eviction, not key shape. Losing this list does not cost a
    // cache miss -- it silently produces an incomplete timeline -- so it belongs where
    // volatile-ttl protects it rather than where allkeys-lru can drop it under load.
    assertThat(cache.stored()).containsOnlyKeys("celeb:followees:me");
  }

  @Test
  @DisplayName("serves a cached list without calling user-service")
  void servesFromCache() {
    cache.prime("celeb:followees:me", List.of("star-9"));

    assertThat(client().celebrityFollowees("me")).containsExactly("star-9");
    server.verify();
  }

  @Test
  @DisplayName("degrades to no celebrities when user-service fails")
  void degradesOnFailure() {
    // A timeline missing its celebrity tweets is visibly incomplete but still useful. One
    // that 500s because a dependency is slow is not, and the materialised half was fine.
    server
        .expect(requestTo("http://users/v1/users/me/following/celebrities"))
        .andRespond(withServerError());

    assertThat(client().celebrityFollowees("me")).isEmpty();
  }
}
