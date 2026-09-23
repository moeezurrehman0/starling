/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.twitterclone.contracts.TweetItem;
import dev.twitterclone.tweet.config.SecurityConfig;
import dev.twitterclone.tweet.domain.TweetService;
import dev.twitterclone.tweet.search.SearchIndex;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract of {@link SearchController}.
 *
 * <p>The behaviour worth pinning is the split of responsibility: Postgres decides which tweets
 * match, DynamoDB decides what they currently are, and a hit whose tweet has since been deleted
 * disappears from the results without breaking the page after it.
 */
@WebMvcTest(controllers = SearchController.class)
@Import({SecurityConfig.class, ErrorHandler.class})
class SearchControllerTest {

  private static final Instant T1 = Instant.parse("2025-03-01T12:00:00Z");
  private static final Instant T2 = Instant.parse("2025-03-01T11:00:00Z");

  @Autowired private MockMvc mvc;

  @MockitoBean private SearchIndex index;
  @MockitoBean private TweetService tweets;
  @MockitoBean private JwtDecoder jwtDecoder;

  private static SearchIndex.Hit hit(String id, Instant at) {
    return new SearchIndex.Hit(id, "author-" + id, "body of " + id, at);
  }

  private static TweetItem item(String id, Instant at) {
    return TweetItem.builder()
        .tweetId(id)
        .authorId("author-" + id)
        .text("body of " + id)
        .createdAt(at)
        .likeCount(3)
        .build();
  }

  @Nested
  @DisplayName("querying")
  class Querying {

    @Test
    @DisplayName("is readable without a token, because search is how a visitor arrives")
    void anonymous() throws Exception {
      when(index.search(anyString(), anyInt(), any())).thenReturn(List.of());

      mvc.perform(get("/v1/search/tweets").param("q", "coffee")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("renders matches from DynamoDB, not from the index")
    void hydratesFromTheSourceOfTruth() throws Exception {
      when(index.search(anyString(), anyInt(), any())).thenReturn(List.of(hit("t1", T1)));
      when(tweets.byIds(List.of("t1"))).thenReturn(Map.of("t1", item("t1", T1)));

      mvc.perform(get("/v1/search/tweets").param("q", "coffee"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items[0].id").value("t1"))
          // The like count comes from the tweet row, which the index does not carry at all.
          .andExpect(jsonPath("$.items[0].likeCount").value(3));
    }

    @Test
    @DisplayName("drops a hit whose tweet has since been deleted")
    void deletedTweetsFallOut() throws Exception {
      when(index.search(anyString(), anyInt(), any()))
          .thenReturn(List.of(hit("t1", T1), hit("gone", T2)));
      when(tweets.byIds(List.of("t1", "gone"))).thenReturn(Map.of("t1", item("t1", T1)));

      mvc.perform(get("/v1/search/tweets").param("q", "coffee"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items.length()").value(1))
          .andExpect(jsonPath("$.items[0].id").value("t1"));
    }

    @Test
    @DisplayName("a blank query returns nothing without touching the database")
    void blankQuery() throws Exception {
      mvc.perform(get("/v1/search/tweets").param("q", "   "))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items.length()").value(0));

      verify(index, never()).search(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("no matches means an empty page with no cursor")
    void noMatches() throws Exception {
      when(index.search(anyString(), anyInt(), any())).thenReturn(List.of());

      mvc.perform(get("/v1/search/tweets").param("q", "nothing"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.items.length()").value(0))
          .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("a missing q is a 400, not an empty result")
    void missingQuery() throws Exception {
      mvc.perform(get("/v1/search/tweets")).andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("paging")
  class Paging {

    @Test
    @DisplayName("clamps an oversized limit rather than refusing it")
    void clampsLimit() throws Exception {
      when(index.search(anyString(), anyInt(), any())).thenReturn(List.of());

      mvc.perform(get("/v1/search/tweets").param("q", "coffee").param("limit", "10000"))
          .andExpect(status().isOk());

      ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
      verify(index).search(anyString(), limit.capture(), any());
      assertThat(limit.getValue()).isEqualTo(SearchIndex.MAX_LIMIT);
    }

    @Test
    @DisplayName("a short page ends pagination")
    void shortPageHasNoCursor() throws Exception {
      when(index.search(anyString(), anyInt(), any())).thenReturn(List.of(hit("t1", T1)));
      when(tweets.byIds(List.of("t1"))).thenReturn(Map.of("t1", item("t1", T1)));

      mvc.perform(get("/v1/search/tweets").param("q", "coffee").param("limit", "5"))
          .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("a full page returns a cursor that round-trips")
    void fullPageHasCursor() throws Exception {
      when(index.search(anyString(), anyInt(), any()))
          .thenReturn(List.of(hit("t1", T1), hit("t2", T2)));
      when(tweets.byIds(List.of("t1", "t2")))
          .thenReturn(Map.of("t1", item("t1", T1), "t2", item("t2", T2)));

      String body =
          mvc.perform(get("/v1/search/tweets").param("q", "coffee").param("limit", "2"))
              .andExpect(jsonPath("$.nextCursor").exists())
              .andReturn()
              .getResponse()
              .getContentAsString();

      String cursor = body.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
      assertThat(SearchController.parse(cursor)).contains(new SearchIndex.Cursor(T2, "t2"));
    }

    @Test
    @DisplayName("the cursor comes from the index, not from what survived hydration")
    void cursorSurvivesAFullyDeletedPage() throws Exception {
      when(index.search(anyString(), anyInt(), any()))
          .thenReturn(List.of(hit("gone1", T1), hit("gone2", T2)));
      when(tweets.byIds(List.of("gone1", "gone2"))).thenReturn(Map.of());

      // Every hit on this page is deleted. The page renders empty, but pagination must
      // continue: a cursor derived from the rendered list would be null and would hide
      // everything behind the deleted rows.
      mvc.perform(get("/v1/search/tweets").param("q", "coffee").param("limit", "2"))
          .andExpect(jsonPath("$.items.length()").value(0))
          .andExpect(jsonPath("$.nextCursor").exists());
    }

    @Test
    @DisplayName("resumes from a supplied cursor")
    void resumes() throws Exception {
      when(index.search(anyString(), anyInt(), any())).thenReturn(List.of());
      String cursor =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString((T2 + "|t2").getBytes(java.nio.charset.StandardCharsets.UTF_8));

      mvc.perform(get("/v1/search/tweets").param("q", "coffee").param("cursor", cursor))
          .andExpect(status().isOk());

      ArgumentCaptor<SearchIndex.Cursor> captured =
          ArgumentCaptor.forClass(SearchIndex.Cursor.class);
      verify(index).search(anyString(), anyInt(), captured.capture());
      assertThat(captured.getValue()).isEqualTo(new SearchIndex.Cursor(T2, "t2"));
    }

    @Test
    @DisplayName("a corrupt cursor starts from the beginning rather than failing")
    void corruptCursor() throws Exception {
      when(index.search(anyString(), anyInt(), any())).thenReturn(List.of());

      mvc.perform(get("/v1/search/tweets").param("q", "coffee").param("cursor", "!!!not-base64!!!"))
          .andExpect(status().isOk());

      verify(index).search(anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("a well-formed but meaningless cursor also starts from the beginning")
    void cursorWithoutSeparator() throws Exception {
      when(index.search(anyString(), anyInt(), any())).thenReturn(List.of());
      String cursor =
          java.util.Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString("no-separator".getBytes(java.nio.charset.StandardCharsets.UTF_8));

      mvc.perform(get("/v1/search/tweets").param("q", "coffee").param("cursor", cursor))
          .andExpect(status().isOk());

      verify(index).search(anyString(), anyInt(), org.mockito.ArgumentMatchers.isNull());
    }
  }
}
