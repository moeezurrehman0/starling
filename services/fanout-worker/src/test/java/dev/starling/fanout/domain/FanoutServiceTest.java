/* SPDX-License-Identifier: MIT */
package dev.starling.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.starling.contracts.TimelineEntryItem;
import dev.starling.contracts.UserItem;
import dev.starling.fanout.config.FanoutProperties;
import dev.starling.fanout.persistence.FollowerRepository;
import dev.starling.fanout.persistence.TimelineWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FanoutService")
class FanoutServiceTest {

  private static final FanoutProperties PROPERTIES =
      new FanoutProperties(
          "arn:stream", Duration.ofSeconds(1), Duration.ofSeconds(5), 100, 50_000, true);

  @Mock private DynamoDbTable<UserItem> users;
  @Mock private FollowerRepository followers;
  @Mock private TimelineWriter timelines;

  private FanoutService service() {
    return new FanoutService(users, followers, timelines, PROPERTIES);
  }

  private void author(String id, boolean celebrity) {
    when(users.getItem(Key.builder().partitionValue(id).build()))
        .thenReturn(
            UserItem.builder()
                .userId(id)
                .handle(id)
                .displayName(id)
                .followerCount(0L)
                .celebrity(celebrity)
                .createdAt(Instant.EPOCH)
                .build());
  }

  /** Records every entry handed to the writer, across all batches. */
  private List<TimelineEntryItem> captureWrites() {
    List<TimelineEntryItem> all = new ArrayList<>();
    when(timelines.write(any()))
        .thenAnswer(
            invocation -> {
              Collection<TimelineEntryItem> batch = invocation.getArgument(0);
              all.addAll(batch);
              return batch.size();
            });
    return all;
  }

  @Nested
  @DisplayName("ordinary authors")
  class Ordinary {

    @Test
    @DisplayName("writes one entry per follower")
    void fansOut() {
      author("bob", false);
      when(followers.followers(eq("bob"), eq(Optional.empty()), anyInt()))
          .thenReturn(new FollowerRepository.Page(List.of("f1", "f2"), Optional.empty()));
      List<TimelineEntryItem> written = captureWrites();

      FanoutService.Result result = service().fanOut("t1", "bob");

      assertThat(result.outcome()).isEqualTo(FanoutService.Outcome.FANNED_OUT);
      assertThat(written).extracting(TimelineEntryItem::ownerId).contains("f1", "f2");
    }

    @Test
    @DisplayName("writes into the author's own timeline too")
    void includesTheAuthor() {
      author("bob", false);
      when(followers.followers(anyString(), any(), anyInt()))
          .thenReturn(new FollowerRepository.Page(List.of(), Optional.empty()));
      List<TimelineEntryItem> written = captureWrites();

      service().fanOut("t1", "bob");

      // An author who cannot see their own tweet in their own feed is the most visible
      // possible bug, and it costs exactly one write.
      assertThat(written).extracting(TimelineEntryItem::ownerId).containsExactly("bob");
    }

    @Test
    @DisplayName("follows the cursor through every page of followers")
    void pagesThroughFollowers() {
      author("bob", false);
      when(followers.followers(eq("bob"), eq(Optional.empty()), anyInt()))
          .thenReturn(new FollowerRepository.Page(List.of("f1"), Optional.of("f1")));
      when(followers.followers(eq("bob"), eq(Optional.of("f1")), anyInt()))
          .thenReturn(new FollowerRepository.Page(List.of("f2"), Optional.empty()));
      List<TimelineEntryItem> written = captureWrites();

      // Stopping at page one would give the author's most recent followers a timeline and
      // everyone else silence -- with nothing anywhere to indicate it happened.
      service().fanOut("t1", "bob");

      assertThat(written).extracting(TimelineEntryItem::ownerId).contains("f1", "f2");
    }

    @Test
    @DisplayName("stamps every entry with the same expiry")
    void setsTtl() {
      author("bob", false);
      when(followers.followers(anyString(), any(), anyInt()))
          .thenReturn(new FollowerRepository.Page(List.of("f1"), Optional.empty()));
      List<TimelineEntryItem> written = captureWrites();

      service().fanOut("t1", "bob");

      assertThat(written)
          .allSatisfy(
              entry -> assertThat(entry.expiresAt()).isGreaterThan(Instant.now().getEpochSecond()));
    }

    @Test
    @DisplayName("records the real author on every entry, not the owner")
    void recordsTheAuthor() {
      author("bob", false);
      when(followers.followers(anyString(), any(), anyInt()))
          .thenReturn(new FollowerRepository.Page(List.of("f1"), Optional.empty()));
      List<TimelineEntryItem> written = captureWrites();

      service().fanOut("t1", "bob");

      assertThat(written).allSatisfy(entry -> assertThat(entry.authorId()).isEqualTo("bob"));
    }
  }

  @Nested
  @DisplayName("celebrity authors")
  class Celebrities {

    @Test
    @DisplayName("are never fanned out to followers")
    void skipped() {
      author("star", true);
      List<TimelineEntryItem> written = captureWrites();

      FanoutService.Result result = service().fanOut("t1", "star");

      // The entire reason this service exists in this shape. Ten million writes triggered by
      // one post is not a slow path, it is an outage; timeline-service pulls instead.
      assertThat(result.outcome()).isEqualTo(FanoutService.Outcome.CELEBRITY_SKIPPED);
      verify(followers, never()).followers(anyString(), any(), anyInt());
      assertThat(written).extracting(TimelineEntryItem::ownerId).containsExactly("star");
    }
  }

  @Nested
  @DisplayName("edge cases")
  class Edges {

    @Test
    @DisplayName("a deleted author is skipped rather than failing the record")
    void authorGone() {
      when(users.getItem(any(Key.class))).thenReturn(null);

      // The account was deleted between the tweet being written and the record being read.
      // There is nobody left to fan out on behalf of, and failing would stall the shard.
      assertThat(service().fanOut("t1", "ghost").outcome())
          .isEqualTo(FanoutService.Outcome.AUTHOR_GONE);
      verify(timelines, never()).write(any());
    }

    @Test
    @DisplayName("truncates rather than letting one tweet consume the shard's budget")
    void truncates() {
      FanoutProperties tight =
          new FanoutProperties(
              "arn:stream", Duration.ofSeconds(1), Duration.ofSeconds(5), 100, 2, true);
      author("bob", false);
      when(followers.followers(eq("bob"), any(), anyInt()))
          .thenReturn(new FollowerRepository.Page(List.of("f1", "f2", "f3"), Optional.of("f3")));
      captureWrites();

      // A backstop for an author who crossed the celebrity threshold without the flag being
      // set: an eventually consistent follower count, or a promotion that failed halfway.
      FanoutService.Result result =
          new FanoutService(users, followers, timelines, tight).fanOut("t1", "bob");

      assertThat(result.outcome()).isEqualTo(FanoutService.Outcome.TRUNCATED);
    }

    @Test
    @DisplayName("an author with no followers still gets their own entry")
    void noFollowers() {
      author("bob", false);
      when(followers.followers(anyString(), any(), anyInt()))
          .thenReturn(new FollowerRepository.Page(List.of(), Optional.empty()));
      List<TimelineEntryItem> written = captureWrites();

      assertThat(service().fanOut("t1", "bob").timelinesWritten()).isEqualTo(1);
      assertThat(written).extracting(TimelineEntryItem::ownerId).containsExactly("bob");
    }
  }
}
