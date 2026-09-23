/* SPDX-License-Identifier: MIT */
package dev.twitterclone.timeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.twitterclone.timeline.config.TimelineProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("TweetFeedClient")
class TweetFeedClientTest {

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
  private RecordingCache celeb;
  private RecordingCache main;

  @BeforeEach
  void setUp() {
    builder = RestClient.builder().baseUrl("http://tweets");
    server = MockRestServiceServer.bindTo(builder).build();
    celeb = new RecordingCache("celeb");
    main = new RecordingCache("main");
  }

  private TweetFeedClient client() {
    return new TweetFeedClient(builder.build(), celeb, main, PROPERTIES);
  }

  private static TweetView tweet(String id) {
    return new TweetView(id, "star", "hi", List.of(), null, null, Instant.EPOCH, 0L);
  }

  @Nested
  @DisplayName("celebrity feeds")
  class CelebrityFeeds {

    @Test
    @DisplayName("pulls a celebrity's recent tweets with the configured page size")
    void pulls() {
      server
          .expect(requestTo("http://tweets/v1/users/star/tweets?limit=50"))
          .andRespond(
              withSuccess(
                  "{\"items\":[{\"id\":\"t2\",\"authorId\":\"star\",\"text\":\"hi\","
                      + "\"mediaKeys\":[],\"createdAt\":\"1970-01-01T00:00:00Z\",\"likeCount\":0}]}",
                  MediaType.APPLICATION_JSON));

      assertThat(client().recentByAuthor("star")).extracting(TweetView::id).containsExactly("t2");
      server.verify();
    }

    @Test
    @DisplayName("caches per author, not per reader")
    void cachedPerAuthor() {
      server
          .expect(requestTo("http://tweets/v1/users/star/tweets?limit=50"))
          .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

      client().recentByAuthor("star");

      // Keying this by follower would defeat it completely. The value of the entry is that
      // one cached list renders a million timelines -- which is the entire reason a
      // celebrity's tweets are not fanned out in the first place.
      assertThat(celeb.stored()).containsOnlyKeys("celeb:feed:star");
      assertThat(main.stored()).isEmpty();
    }

    @Test
    @DisplayName("degrades to an empty feed when tweet-service fails")
    void degrades() {
      server
          .expect(requestTo("http://tweets/v1/users/star/tweets?limit=50"))
          .andRespond(withServerError());

      assertThat(client().recentByAuthor("star")).isEmpty();
    }
  }

  @Nested
  @DisplayName("hydration")
  class Hydration {

    @Test
    @DisplayName("batches the misses into one request")
    void batchesMisses() {
      server
          .expect(requestTo("http://tweets/v1/tweets?ids=a&ids=b"))
          .andRespond(
              withSuccess(
                  "{\"items\":{\"a\":{\"id\":\"a\",\"authorId\":\"x\",\"text\":\"t\","
                      + "\"mediaKeys\":[],\"createdAt\":\"1970-01-01T00:00:00Z\",\"likeCount\":0}}}",
                  MediaType.APPLICATION_JSON));

      assertThat(client().hydrate(List.of("a", "b"))).containsOnlyKeys("a");
      server.verify();
    }

    @Test
    @DisplayName("caches individual bodies in the main tier")
    void cachesInMainTier() {
      server
          .expect(requestTo("http://tweets/v1/tweets?ids=a"))
          .andRespond(
              withSuccess(
                  "{\"items\":{\"a\":{\"id\":\"a\",\"authorId\":\"x\",\"text\":\"t\","
                      + "\"mediaKeys\":[],\"createdAt\":\"1970-01-01T00:00:00Z\",\"likeCount\":0}}}",
                  MediaType.APPLICATION_JSON));

      client().hydrate(List.of("a"));

      // The long tail: read by roughly one person each and individually cheap to refetch.
      // Putting these in the celebrity tier would evict the few keys whose loss is expensive.
      assertThat(main.stored()).containsOnlyKeys("tweet:a");
      assertThat(celeb.stored()).isEmpty();
    }

    @Test
    @DisplayName("a fully cached page makes no request at all")
    void allCached() {
      main.prime("tweet:a", tweet("a"));

      assertThat(client().hydrate(List.of("a"))).containsOnlyKeys("a");
      server.verify();
    }

    @Test
    @DisplayName("an empty list makes no request")
    void empty() {
      // tweet-service would reject a request with no ids, so a page that hydrated entirely
      // from cache must not produce a call with an empty parameter.
      assertThat(client().hydrate(List.of())).isEmpty();
      server.verify();
    }

    @Test
    @DisplayName("returns what it has when tweet-service fails")
    void degrades() {
      main.prime("tweet:a", tweet("a"));
      server.expect(requestTo("http://tweets/v1/tweets?ids=b")).andRespond(withServerError());

      assertThat(client().hydrate(List.of("a", "b"))).containsOnlyKeys("a");
    }
  }
}
