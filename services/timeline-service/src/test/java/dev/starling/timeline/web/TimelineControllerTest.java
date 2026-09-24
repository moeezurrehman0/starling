/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.starling.timeline.config.SecurityConfig;
import dev.starling.timeline.domain.TimelineService;
import dev.starling.timeline.domain.TweetView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract of {@link TimelineController}.
 *
 * <p>Runs the real filter chain, because the single most important property of this endpoint is
 * that it cannot be pointed at somebody else's timeline, and that property lives in the interaction
 * between the security config and the handler's signature rather than in either alone.
 */
@WebMvcTest(TimelineController.class)
@Import({SecurityConfig.class, ErrorHandler.class})
@DisplayName("TimelineController")
class TimelineControllerTest {

  private static final String ME = "user-1";

  @Autowired private MockMvc mvc;

  @MockitoBean private TimelineService timelines;

  // The slice does not start a resource server, so the decoder that SecurityConfig expects
  // would be missing and the context would fail to refresh before any test ran.
  @MockitoBean private JwtDecoder jwtDecoder;

  private static TweetView tweet(String id) {
    return new TweetView(id, "author", "hello", List.of(), null, null, Instant.EPOCH, 3L);
  }

  @Test
  @DisplayName("returns the caller's own timeline")
  void readsOwnTimeline() throws Exception {
    when(timelines.home(eq(ME), any(), anyInt()))
        .thenReturn(new TimelineService.Timeline(List.of(tweet("t1")), Optional.of("t1")));

    mvc.perform(get("/v1/timelines/home").with(jwt().jwt(j -> j.subject(ME))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value("t1"))
        .andExpect(jsonPath("$.nextCursor").value("t1"));
  }

  @Test
  @DisplayName("the owner comes from the token, never from the request")
  void ownerComesFromTheToken() throws Exception {
    when(timelines.home(anyString(), any(), anyInt()))
        .thenReturn(new TimelineService.Timeline(List.of(), Optional.empty()));

    // A userId query parameter is not merely ignored -- there is nowhere for it to be read.
    // That is the authorisation rule: an unauthorised read is unrepresentable rather than
    // merely refused, so no future handler can forget to check it.
    mvc.perform(
            get("/v1/timelines/home")
                .queryParam("userId", "someone-else")
                .with(jwt().jwt(j -> j.subject(ME))))
        .andExpect(status().isOk());

    verify(timelines).home(eq(ME), any(), anyInt());
  }

  @Test
  @DisplayName("requires a token")
  void requiresAToken() throws Exception {
    // There is no such thing as the logged-out user's home timeline, so this is a 401 and
    // not, as in tweet-service, a public read with a null principal.
    mvc.perform(get("/v1/timelines/home")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("passes the cursor through")
  void passesCursor() throws Exception {
    when(timelines.home(eq(ME), eq(Optional.of("c1")), anyInt()))
        .thenReturn(new TimelineService.Timeline(List.of(), Optional.empty()));

    mvc.perform(
            get("/v1/timelines/home")
                .queryParam("cursor", "c1")
                .with(jwt().jwt(j -> j.subject(ME))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextCursor").doesNotExist());

    verify(timelines).home(ME, Optional.of("c1"), 20);
  }

  @Test
  @DisplayName("rejects a page size above the cap")
  void rejectsOversizePage() throws Exception {
    // Unbounded would let one request ask for a user's entire history, and the merge holds
    // both halves in memory while it runs.
    mvc.perform(
            get("/v1/timelines/home")
                .queryParam("limit", "500")
                .with(jwt().jwt(j -> j.subject(ME))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Invalid request"));
  }

  @Test
  @DisplayName("rejects a non-positive page size")
  void rejectsZeroPage() throws Exception {
    mvc.perform(
            get("/v1/timelines/home").queryParam("limit", "0").with(jwt().jwt(j -> j.subject(ME))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("health passes the filter chain without a token")
  void healthIsOpen() throws Exception {
    // 404 rather than 401 is the assertion that matters: the slice registers no actuator
    // endpoints, so reaching a 404 proves the request passed the filter chain rather than
    // being refused by it. Kubernetes probes carry no credentials, and a 401 here would make
    // every pod fail readiness and never receive traffic.
    mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
  }
}
