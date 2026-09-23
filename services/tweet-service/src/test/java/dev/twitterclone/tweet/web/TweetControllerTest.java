/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.twitterclone.contracts.IdempotencyItem;
import dev.twitterclone.contracts.TweetItem;
import dev.twitterclone.tweet.config.SecurityConfig;
import dev.twitterclone.tweet.domain.MediaService;
import dev.twitterclone.tweet.domain.TweetService;
import dev.twitterclone.tweet.persistence.IdempotencyRepository;
import dev.twitterclone.tweet.persistence.TweetRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract of {@link TweetController}.
 *
 * <p>Runs the real filter chain. Which endpoints are reachable without a token, and what happens
 * when a write is retried, are the two things in this service that are both consequential and easy
 * to break silently, so both are asserted here rather than assumed.
 */
@WebMvcTest(controllers = TweetController.class)
@Import({SecurityConfig.class, ErrorHandler.class})
class TweetControllerTest {

  private static final String CALLER = "0193f0a0-0000-7000-8000-000000000001";
  private static final String TWEET = "0193f0a0-0000-7000-8000-0000000000aa";

  @Autowired private MockMvc mvc;

  @MockitoBean private TweetService tweets;
  @MockitoBean private MediaService media;
  @MockitoBean private IdempotencyRepository idempotency;

  /** Stubs signature verification only; {@code jwt()} supplies the verified principal. */
  @MockitoBean private JwtDecoder jwtDecoder;

  private static TweetItem tweet(String id, String authorId) {
    return TweetItem.builder()
        .tweetId(id)
        .authorId(authorId)
        .text("hello")
        .mediaKeys(List.of())
        .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
        .likeCount(7)
        .build();
  }

  @Test
  @DisplayName("posting returns 201 with a Location header")
  void postsATweet() throws Exception {
    when(tweets.post(eq(CALLER), eq("hello"), any(), any(), any()))
        .thenReturn(tweet(TWEET, CALLER));

    mvc.perform(
            post("/v1/tweets")
                .with(jwt().jwt(j -> j.subject(CALLER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/v1/tweets/" + TWEET))
        .andExpect(jsonPath("$.id").value(TWEET))
        .andExpect(jsonPath("$.likeCount").value(7));
  }

  @Test
  @DisplayName("posting without a token is 401, not 500")
  void postRequiresAToken() throws Exception {
    mvc.perform(
            post("/v1/tweets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
        .andExpect(status().isUnauthorized());
    verify(tweets, never()).post(anyString(), anyString(), any(), any(), any());
  }

  @Test
  @DisplayName("no Idempotency-Key means no claim is taken")
  void withoutAKeyNothingIsClaimed() throws Exception {
    when(tweets.post(any(), any(), any(), any(), any())).thenReturn(tweet(TWEET, CALLER));

    mvc.perform(
            post("/v1/tweets")
                .with(jwt().jwt(j -> j.subject(CALLER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
        .andExpect(status().isCreated());

    verify(idempotency, never()).claim(any(), any(), any());
  }

  @Test
  @DisplayName("a fresh Idempotency-Key does the work and records the result")
  void freshKey() throws Exception {
    when(idempotency.claim(eq(CALLER), eq("k1"), anyString()))
        .thenReturn(
            new IdempotencyRepository.Claim(
                IdempotencyRepository.Claim.Outcome.FRESH, Optional.empty()));
    when(tweets.post(any(), any(), any(), any(), any())).thenReturn(tweet(TWEET, CALLER));

    mvc.perform(
            post("/v1/tweets")
                .with(jwt().jwt(j -> j.subject(CALLER)))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
        .andExpect(status().isCreated());

    verify(idempotency).complete(CALLER, "k1", 201, TWEET);
  }

  @Test
  @DisplayName("a replayed key returns 200, not 201, and does not post again")
  void replayedKey() throws Exception {
    when(idempotency.claim(eq(CALLER), eq("k1"), anyString()))
        .thenReturn(
            new IdempotencyRepository.Claim(
                IdempotencyRepository.Claim.Outcome.REPLAYED, Optional.of(completed(TWEET))));
    when(tweets.byId(TWEET)).thenReturn(Optional.of(tweet(TWEET, CALLER)));

    mvc.perform(
            post("/v1/tweets")
                .with(jwt().jwt(j -> j.subject(CALLER)))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
        // 201 here would make a retrying client double-count its own posts.
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TWEET));

    verify(tweets, never()).post(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a key reused for a different body is a 409")
  void mismatchedKey() throws Exception {
    when(idempotency.claim(eq(CALLER), eq("k1"), anyString()))
        .thenReturn(
            new IdempotencyRepository.Claim(
                IdempotencyRepository.Claim.Outcome.MISMATCHED, Optional.empty()));

    mvc.perform(
            post("/v1/tweets")
                .with(jwt().jwt(j -> j.subject(CALLER)))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"different\"}"))
        .andExpect(status().isConflict());

    verify(tweets, never()).post(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("a replayed key whose work is still in flight is a 409 with Retry-After")
  void inFlightKey() throws Exception {
    when(idempotency.claim(eq(CALLER), eq("k1"), anyString()))
        .thenReturn(
            new IdempotencyRepository.Claim(
                IdempotencyRepository.Claim.Outcome.REPLAYED, Optional.of(inFlight())));

    mvc.perform(
            post("/v1/tweets")
                .with(jwt().jwt(j -> j.subject(CALLER)))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
        .andExpect(status().isConflict())
        .andExpect(header().string("Retry-After", "1"));
  }

  @Test
  @DisplayName("a rejected post releases the key so the client may correct and retry")
  void failedPostReleasesTheKey() throws Exception {
    when(idempotency.claim(eq(CALLER), eq("k1"), anyString()))
        .thenReturn(
            new IdempotencyRepository.Claim(
                IdempotencyRepository.Claim.Outcome.FRESH, Optional.empty()));
    when(tweets.post(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("Tweet is 900 characters, limit is 280"));

    mvc.perform(
            post("/v1/tweets")
                .with(jwt().jwt(j -> j.subject(CALLER)))
                .header("Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
        .andExpect(status().isBadRequest());

    verify(idempotency).release(CALLER, "k1");
    verify(idempotency, never()).complete(any(), any(), anyInt(), any());
  }

  @Test
  @DisplayName("reading a tweet needs no token and reports no like state")
  void anonymousRead() throws Exception {
    when(tweets.byId(TWEET)).thenReturn(Optional.of(tweet(TWEET, CALLER)));

    mvc.perform(get("/v1/tweets/" + TWEET))
        .andExpect(status().isOk())
        // null, not false: an anonymous caller has no like state to report, and false would
        // assert they have not liked it.
        .andExpect(jsonPath("$.likedByMe").doesNotExist());
  }

  @Test
  @DisplayName("an authenticated read reports the caller's like state")
  void authenticatedRead() throws Exception {
    when(tweets.byId(TWEET)).thenReturn(Optional.of(tweet(TWEET, CALLER)));
    when(tweets.hasLiked(TWEET, CALLER)).thenReturn(true);

    mvc.perform(get("/v1/tweets/" + TWEET).with(jwt().jwt(j -> j.subject(CALLER))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likedByMe").value(true));
  }

  @Test
  @DisplayName("a missing tweet is a 404 problem document")
  void missingTweet() throws Exception {
    when(tweets.byId(TWEET)).thenReturn(Optional.empty());

    mvc.perform(get("/v1/tweets/" + TWEET))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Not found"));
  }

  @Test
  @DisplayName("a batch read preserves the caller's order and drops what is gone")
  void batchRead() throws Exception {
    when(tweets.byIds(List.of("a", "b", "c")))
        // Deliberately not in request order: BatchGetItem makes no ordering guarantee.
        .thenReturn(Map.of("c", tweet("c", CALLER), "a", tweet("a", CALLER)));

    mvc.perform(get("/v1/tweets").param("ids", "a", "b", "c"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.a.id").value("a"))
        .andExpect(jsonPath("$.items.b").doesNotExist())
        .andExpect(jsonPath("$.items.c.id").value("c"));
  }

  @Test
  @DisplayName("page size is clamped rather than trusted")
  void pageSizeIsClamped() throws Exception {
    when(tweets.byAuthor(eq(CALLER), any(), anyInt()))
        .thenReturn(new TweetRepository.TweetPage(List.of(), Optional.empty()));

    mvc.perform(get("/v1/tweets/by-author/" + CALLER).param("limit", "10000"))
        .andExpect(status().isOk());

    verify(tweets).byAuthor(eq(CALLER), eq(Optional.empty()), eq(50));
  }

  @Test
  @DisplayName("deleting somebody else's tweet is a 403")
  void deleteForbidden() throws Exception {
    when(tweets.delete(TWEET, CALLER)).thenThrow(new TweetRepository.NotTheAuthorException(TWEET));

    mvc.perform(delete("/v1/tweets/" + TWEET).with(jwt().jwt(j -> j.subject(CALLER))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("deleting a tweet that is already gone is still 204")
  void deleteIsIdempotent() throws Exception {
    when(tweets.delete(TWEET, CALLER)).thenReturn(false);

    mvc.perform(delete("/v1/tweets/" + TWEET).with(jwt().jwt(j -> j.subject(CALLER))))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("liking is authenticated and returns the new count")
  void like() throws Exception {
    when(tweets.like(TWEET, CALLER)).thenReturn(8L);

    mvc.perform(post("/v1/tweets/" + TWEET + "/likes").with(jwt().jwt(j -> j.subject(CALLER))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(8))
        .andExpect(jsonPath("$.likedByMe").value(true));
  }

  @Test
  @DisplayName("liking without a token is 401")
  void likeRequiresAToken() throws Exception {
    mvc.perform(post("/v1/tweets/" + TWEET + "/likes")).andExpect(status().isUnauthorized());
    verify(tweets, never()).like(any(), any());
  }

  @Test
  @DisplayName("an upload grant is issued for the caller, never for a claimed id")
  void uploadGrant() throws Exception {
    when(media.grant(CALLER, "image/png", 1024))
        .thenReturn(
            new MediaService.Grant(
                "media/" + CALLER + "/obj", "https://s3.example/put", Instant.EPOCH));

    mvc.perform(
            post("/v1/media/uploads")
                .with(jwt().jwt(j -> j.subject(CALLER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/png\",\"contentLength\":1024}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.key").value("media/" + CALLER + "/obj"));
  }

  @Test
  @DisplayName("an unsupported media type is a 400, not a 500")
  void badMediaType() throws Exception {
    when(media.grant(any(), any(), anyLong()))
        .thenThrow(new IllegalArgumentException("Unsupported media type: text/html"));

    mvc.perform(
            post("/v1/media/uploads")
                .with(jwt().jwt(j -> j.subject(CALLER)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"text/html\",\"contentLength\":10}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid request"));
  }

  private static IdempotencyItem completed(String resultId) {
    return IdempotencyItem.builder()
        .key(CALLER + "#k1")
        .requestHash("hash")
        .resultId(resultId)
        .statusCode(201)
        .createdAt(Instant.EPOCH)
        .expiresAt(0)
        .build();
  }

  private static IdempotencyItem inFlight() {
    return IdempotencyItem.builder()
        .key(CALLER + "#k1")
        .requestHash("hash")
        .statusCode(IdempotencyItem.IN_FLIGHT)
        .createdAt(Instant.EPOCH)
        .expiresAt(0)
        .build();
  }
}
