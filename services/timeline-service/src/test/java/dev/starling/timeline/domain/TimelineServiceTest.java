/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.starling.contracts.TimelineEntryItem;
import dev.starling.timeline.persistence.TimelineRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TimelineService")
class TimelineServiceTest {

  @Mock private TimelineRepository timelines;
  @Mock private FollowGraphClient followGraph;
  @Mock private TweetFeedClient tweetFeed;

  private TimelineService service() {
    return new TimelineService(timelines, followGraph, tweetFeed);
  }

  /** Ids are compared as strings, so fixed-width digits are enough to fix an order. */
  private static TweetView tweet(String id, String author) {
    return new TweetView(id, author, "body " + id, List.of(), null, null, Instant.EPOCH, 0L);
  }

  private static TimelineEntryItem entry(String owner, String tweetId, String author) {
    return TimelineEntryItem.builder()
        .ownerId(owner)
        .tweetId(tweetId)
        .authorId(author)
        .createdAt(Instant.EPOCH)
        .expiresAt(0L)
        .build();
  }

  /** Hydration returns whatever was asked for, so tests can focus on the merge. */
  private void hydrateEverything() {
    when(tweetFeed.hydrate(any()))
        .thenAnswer(
            invocation -> {
              List<String> ids = invocation.getArgument(0);
              Map<String, TweetView> map = new LinkedHashMap<>();
              ids.forEach(id -> map.put(id, tweet(id, "someone")));
              return map;
            });
  }

  @Nested
  @DisplayName("the hybrid merge")
  class Merge {

    @Test
    @DisplayName("interleaves materialised and celebrity tweets in id order")
    void interleaves() {
      when(timelines.page("me", Optional.empty(), 10))
          .thenReturn(List.of(entry("me", "05", "friend"), entry("me", "01", "friend")));
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of("star"));
      when(tweetFeed.recentByAuthor("star"))
          .thenReturn(List.of(tweet("07", "star"), tweet("03", "star")));
      hydrateEverything();

      // Newest-first across both halves, which is only correct because UUIDv7 makes
      // lexicographic id order the same as chronological order.
      assertThat(service().home("me", Optional.empty(), 10).tweets())
          .extracting(TweetView::id)
          .containsExactly("07", "05", "03", "01");
    }

    @Test
    @DisplayName("a tweet present in both halves appears once")
    void dedupes() {
      // Happens for real: an account promoted to celebrity keeps the rows fan-out already
      // wrote, and those same tweets now also arrive on the pull path.
      when(timelines.page("me", Optional.empty(), 10))
          .thenReturn(List.of(entry("me", "05", "star")));
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of("star"));
      when(tweetFeed.recentByAuthor("star")).thenReturn(List.of(tweet("05", "star")));
      hydrateEverything();

      assertThat(service().home("me", Optional.empty(), 10).tweets()).hasSize(1);
    }

    @Test
    @DisplayName("works with no celebrities at all")
    void noCelebrities() {
      when(timelines.page("me", Optional.empty(), 10))
          .thenReturn(List.of(entry("me", "02", "friend")));
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of());
      hydrateEverything();

      assertThat(service().home("me", Optional.empty(), 10).tweets())
          .extracting(TweetView::id)
          .containsExactly("02");
      verify(tweetFeed, never()).recentByAuthor(anyString());
    }

    @Test
    @DisplayName("works when nothing has been fanned out yet")
    void onlyCelebrities() {
      // A brand-new account following only celebrities has an empty timelines partition, and
      // must still see a timeline rather than an empty page.
      when(timelines.page("me", Optional.empty(), 10)).thenReturn(List.of());
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of("star"));
      when(tweetFeed.recentByAuthor("star")).thenReturn(List.of(tweet("09", "star")));
      hydrateEverything();

      assertThat(service().home("me", Optional.empty(), 10).tweets())
          .extracting(TweetView::id)
          .containsExactly("09");
    }
  }

  @Nested
  @DisplayName("paging")
  class Paging {

    @Test
    @DisplayName("filters celebrity tweets newer than the cursor")
    void honoursCursorOnThePullPath() {
      // The materialised half is filtered by DynamoDB; the pull half is not, because the
      // cached celebrity feed is shared and cannot be per-reader. Forgetting this filter
      // would make page two repeat the newest celebrity tweets forever.
      when(timelines.page("me", Optional.of("05"), 10)).thenReturn(List.of());
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of("star"));
      when(tweetFeed.recentByAuthor("star"))
          .thenReturn(List.of(tweet("09", "star"), tweet("03", "star")));
      hydrateEverything();

      assertThat(service().home("me", Optional.of("05"), 10).tweets())
          .extracting(TweetView::id)
          .containsExactly("03");
    }

    @Test
    @DisplayName("offers no cursor when the page is short")
    void noCursorAtTheEnd() {
      when(timelines.page("me", Optional.empty(), 10))
          .thenReturn(List.of(entry("me", "01", "friend")));
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of());
      hydrateEverything();

      assertThat(service().home("me", Optional.empty(), 10).next()).isEmpty();
    }

    @Test
    @DisplayName("offers the last id as the cursor when the page is full")
    void cursorWhenFull() {
      when(timelines.page("me", Optional.empty(), 2))
          .thenReturn(List.of(entry("me", "05", "f"), entry("me", "04", "f")));
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of());
      hydrateEverything();

      assertThat(service().home("me", Optional.empty(), 2).next()).contains("04");
    }

    @Test
    @DisplayName("the cursor survives a page whose tail was entirely deleted")
    void cursorIsACandidateNotABody() {
      when(timelines.page("me", Optional.empty(), 2))
          .thenReturn(List.of(entry("me", "05", "f"), entry("me", "04", "f")));
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of());
      when(tweetFeed.hydrate(List.of("05", "04"))).thenReturn(Map.of("05", tweet("05", "f")));

      // If the cursor came from the surviving bodies it would be "05" -- the page the client
      // just read -- and the client would ask for it again, forever.
      assertThat(service().home("me", Optional.empty(), 2).next()).contains("04");
    }
  }

  @Nested
  @DisplayName("missing bodies")
  class MissingBodies {

    @Test
    @DisplayName("drops entries whose tweet no longer exists")
    void dropsDeleted() {
      // Fan-out is not transactional with deletion, and could not be without making every
      // delete O(followers). Stale rows are expected, not exceptional.
      when(timelines.page("me", Optional.empty(), 10))
          .thenReturn(List.of(entry("me", "05", "f"), entry("me", "04", "f")));
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of());
      when(tweetFeed.hydrate(List.of("05", "04"))).thenReturn(Map.of("04", tweet("04", "f")));

      assertThat(service().home("me", Optional.empty(), 10).tweets())
          .extracting(TweetView::id)
          .containsExactly("04");
    }

    @Test
    @DisplayName("returns an empty page rather than failing when nothing hydrates")
    void allGone() {
      when(timelines.page("me", Optional.empty(), 10)).thenReturn(List.of(entry("me", "05", "f")));
      when(followGraph.celebrityFollowees("me")).thenReturn(List.of());
      when(tweetFeed.hydrate(any())).thenReturn(Map.of());

      assertThat(service().home("me", Optional.empty(), 10).tweets()).isEmpty();
    }
  }
}
