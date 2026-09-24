/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.starling.contracts.TweetItem;
import dev.starling.tweet.persistence.LikeRepository;
import dev.starling.tweet.persistence.TweetRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The rules governing what may be posted and what a like does to a counter. */
class TweetServiceTest {

  private static final String AUTHOR = "0193f0a0-0000-7000-8000-000000000001";
  private static final String TWEET = "0193f0a0-0000-7000-8000-0000000000aa";

  private TweetRepository tweets;
  private LikeRepository likes;
  private MediaService media;
  private TweetService service;

  @BeforeEach
  void setUp() {
    tweets = mock(TweetRepository.class);
    likes = mock(LikeRepository.class);
    media = mock(MediaService.class);
    when(tweets.create(any())).thenReturn(true);
    when(media.isOwnedBy(anyString(), anyString())).thenReturn(true);
    service = new TweetService(tweets, likes, media);
  }

  private static TweetItem stored(long likeCount) {
    return TweetItem.builder()
        .tweetId(TWEET)
        .authorId(AUTHOR)
        .text("hello")
        .mediaKeys(List.of())
        .createdAt(Instant.EPOCH)
        .likeCount(likeCount)
        .build();
  }

  private TweetItem post(String text) {
    return service.post(AUTHOR, text, List.of(), Optional.empty(), Optional.empty());
  }

  @Test
  @DisplayName("a posted tweet gets a generated id and a zero like count")
  void postAssignsIdentity() {
    TweetItem posted = post("hello");

    assertThat(posted.tweetId()).isNotBlank();
    assertThat(posted.authorId()).isEqualTo(AUTHOR);
    assertThat(posted.likeCount()).isZero();
    verify(tweets).create(posted);
  }

  @Test
  @DisplayName("ids are time-ordered, because every downstream reader sorts by them")
  void idsAreTimeOrdered() {
    // UUIDv7. If this ever stops holding, timelines silently lose their ordering rather than
    // failing, which is the worst way for it to break.
    assertThat(post("a").tweetId()).isLessThan(post("b").tweetId());
  }

  @Test
  @DisplayName("length is counted in code points, not UTF-16 units")
  void lengthIsCountedInCodePoints() {
    // Each of these is two chars and one code point. Counted with String.length() a tweet of
    // them would be cut off at half the advertised limit while Latin text passed.
    String emoji = "\uD83D\uDE00".repeat(TweetItem.MAX_TEXT_LENGTH);

    assertThat(post(emoji).text()).isEqualTo(emoji);
  }

  @Test
  @DisplayName("one code point over the limit is refused")
  void tooLongIsRefused() {
    String tooLong = "a".repeat(TweetItem.MAX_TEXT_LENGTH + 1);

    assertThatThrownBy(() -> post(tooLong))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit is " + TweetItem.MAX_TEXT_LENGTH);
    verify(tweets, never()).create(any());
  }

  @Test
  @DisplayName("an empty tweet with no media and no retweet is refused")
  void emptyIsRefused() {
    assertThatThrownBy(() -> post("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must have text, media or be a retweet");
  }

  @Test
  @DisplayName("a bare retweet needs no text of its own")
  void bareRetweetIsAllowed() {
    when(tweets.findById("src")).thenReturn(Optional.of(stored(0)));

    TweetItem posted = service.post(AUTHOR, "", List.of(), Optional.empty(), Optional.of("src"));

    assertThat(posted.retweetOfTweetId()).isEqualTo("src");
  }

  @Test
  @DisplayName("more than four images is refused")
  void tooMuchMediaIsRefused() {
    List<String> five = List.of("a", "b", "c", "d", "e");

    assertThatThrownBy(() -> service.post(AUTHOR, "hi", five, Optional.empty(), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At most 4");
  }

  @Test
  @DisplayName("a media key the caller was never granted is refused")
  void foreignMediaIsRefused() {
    when(media.isOwnedBy(AUTHOR, "media/someone-else/x")).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.post(
                    AUTHOR,
                    "hi",
                    List.of("media/someone-else/x"),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not issued to this user");
    // Without the check, naming a key would publish somebody else's unpublished upload.
    verify(tweets, never()).create(any());
  }

  @Test
  @DisplayName("a reply to a tweet that does not exist is refused")
  void danglingReplyIsRefused() {
    when(tweets.findById("gone")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.post(AUTHOR, "hi", List.of(), Optional.of("gone"), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("replyTo");
  }

  @Test
  @DisplayName("a tweet cannot be both a reply and a retweet")
  void replyAndRetweetIsRefused() {
    assertThatThrownBy(
            () -> service.post(AUTHOR, "hi", List.of(), Optional.of("a"), Optional.of("b")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be both");
  }

  @Test
  @DisplayName("an id collision is fatal rather than retried")
  void idCollisionIsFatal() {
    when(tweets.create(any())).thenReturn(false);

    // Retrying would hide a broken generator until ids started repeating in bulk.
    assertThatThrownBy(() -> post("hello")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("a first like moves the counter")
  void firstLikeCounts() {
    when(likes.like(TWEET, AUTHOR)).thenReturn(true);
    when(tweets.adjustLikeCount(TWEET, 1)).thenReturn(Optional.of(8L));

    assertThat(service.like(TWEET, AUTHOR)).isEqualTo(8L);
  }

  @Test
  @DisplayName("liking twice is a success that does not move the counter")
  void repeatedLikeIsIdempotent() {
    when(likes.like(TWEET, AUTHOR)).thenReturn(false);
    when(tweets.findById(TWEET)).thenReturn(Optional.of(stored(7)));

    assertThat(service.like(TWEET, AUTHOR)).isEqualTo(7L);
    // Failing here would make a retried request an error the client has to learn to ignore.
    verify(tweets, never()).adjustLikeCount(anyString(), anyInt());
  }

  @Test
  @DisplayName("liking a tweet deleted mid-flight does not blow up")
  void likeOnDeletedTweet() {
    when(likes.like(TWEET, AUTHOR)).thenReturn(true);
    when(tweets.adjustLikeCount(TWEET, 1)).thenReturn(Optional.empty());

    assertThat(service.like(TWEET, AUTHOR)).isZero();
  }

  @Test
  @DisplayName("unliking decrements")
  void unlikeDecrements() {
    when(likes.unlike(TWEET, AUTHOR)).thenReturn(true);
    when(tweets.adjustLikeCount(TWEET, -1)).thenReturn(Optional.of(6L));

    assertThat(service.unlike(TWEET, AUTHOR)).isEqualTo(6L);
  }

  @Test
  @DisplayName("a count driven negative by a race is floored at zero on read")
  void countIsFlooredAtZero() {
    when(likes.unlike(TWEET, AUTHOR)).thenReturn(true);
    when(tweets.adjustLikeCount(TWEET, -1)).thenReturn(Optional.of(-1L));

    // A conditional decrement would instead fail and leave the row deleted with the count
    // unchanged, which is a worse outcome than a count that is briefly one too low.
    assertThat(service.unlike(TWEET, AUTHOR)).isZero();
  }

  @Test
  @DisplayName("unliking something never liked does not move the counter")
  void repeatedUnlikeIsIdempotent() {
    when(likes.unlike(TWEET, AUTHOR)).thenReturn(false);
    when(tweets.findById(TWEET)).thenReturn(Optional.of(stored(7)));

    assertThat(service.unlike(TWEET, AUTHOR)).isEqualTo(7L);
    verify(tweets, never()).adjustLikeCount(anyString(), anyInt());
  }

  @Test
  @DisplayName("delete reports whether anything was removed")
  void deleteReportsOutcome() {
    when(tweets.delete(TWEET, AUTHOR)).thenReturn(Optional.of(stored(0)));
    assertThat(service.delete(TWEET, AUTHOR)).isTrue();

    when(tweets.delete(TWEET, AUTHOR)).thenReturn(Optional.empty());
    assertThat(service.delete(TWEET, AUTHOR)).isFalse();
  }

  @Test
  @DisplayName("media keys are stored on the tweet as given")
  void mediaKeysAreStored() {
    ArgumentCaptor<TweetItem> captor = ArgumentCaptor.forClass(TweetItem.class);
    List<String> keys = List.of("media/" + AUTHOR + "/one", "media/" + AUTHOR + "/two");

    service.post(AUTHOR, "hi", keys, Optional.empty(), Optional.empty());

    verify(tweets).create(captor.capture());
    assertThat(captor.getValue().mediaKeys()).isEqualTo(keys);
  }
}
