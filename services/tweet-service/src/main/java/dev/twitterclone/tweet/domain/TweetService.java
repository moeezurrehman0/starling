/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.domain;

import dev.twitterclone.contracts.Ids;
import dev.twitterclone.contracts.TweetItem;
import dev.twitterclone.tweet.persistence.LikeRepository;
import dev.twitterclone.tweet.persistence.TweetRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Posting, deleting, reading and liking tweets. */
@Service
public class TweetService {

  private static final Logger LOG = LoggerFactory.getLogger(TweetService.class);

  /** Most images one tweet may carry. */
  public static final int MAX_MEDIA = 4;

  private final TweetRepository tweets;
  private final LikeRepository likes;
  private final MediaService media;

  public TweetService(TweetRepository tweets, LikeRepository likes, MediaService media) {
    this.tweets = tweets;
    this.likes = likes;
    this.media = media;
  }

  /**
   * Posts a tweet.
   *
   * @param authorId who is posting
   * @param text the body
   * @param mediaKeys keys from previously issued upload grants
   * @param replyTo the tweet being replied to, if any
   * @param retweetOf the tweet being retweeted, if any
   * @return the stored tweet
   */
  public TweetItem post(
      String authorId,
      String text,
      List<String> mediaKeys,
      Optional<String> replyTo,
      Optional<String> retweetOf) {

    validate(authorId, text, mediaKeys, replyTo, retweetOf);

    TweetItem tweet =
        TweetItem.builder()
            // Generated here rather than by DynamoDB, because the id has to exist before the
            // write: it is a UUIDv7, so it also carries the ordering every downstream reader
            // sorts by, and a database-assigned id would not be time-ordered.
            .tweetId(Ids.newId())
            .authorId(authorId)
            .text(text)
            .mediaKeys(mediaKeys)
            .replyToTweetId(replyTo.orElse(null))
            .retweetOfTweetId(retweetOf.orElse(null))
            .createdAt(Instant.now())
            .likeCount(0)
            .build();

    if (!tweets.create(tweet)) {
      // Only reachable if a generated id already existed. That is not a collision worth
      // retrying through -- it means the generator is broken, and silently retrying would
      // hide the fault until ids started repeating in bulk.
      throw new IllegalStateException("Generated tweet id already exists: " + tweet.tweetId());
    }
    return tweet;
  }

  private void validate(
      String authorId,
      String text,
      List<String> mediaKeys,
      Optional<String> replyTo,
      Optional<String> retweetOf) {

    // Code points, not chars. String.length() counts UTF-16 units, so a tweet of emoji would
    // be cut off at 140 of them while the same number of Latin letters passed.
    int length = text.codePointCount(0, text.length());
    if (length > TweetItem.MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException(
          "Tweet is " + length + " characters, limit is " + TweetItem.MAX_TEXT_LENGTH);
    }
    if (text.isBlank() && mediaKeys.isEmpty() && retweetOf.isEmpty()) {
      // A bare retweet legitimately has no text of its own; nothing else may be empty.
      throw new IllegalArgumentException("A tweet must have text, media or be a retweet");
    }
    if (mediaKeys.size() > MAX_MEDIA) {
      throw new IllegalArgumentException("At most " + MAX_MEDIA + " images per tweet");
    }
    mediaKeys.forEach(
        key -> {
          if (!media.isOwnedBy(authorId, key)) {
            // A media key is client-supplied. Without this, posting a tweet that names
            // somebody else's not-yet-published upload would publish it for them.
            throw new IllegalArgumentException("Media key was not issued to this user: " + key);
          }
        });
    if (replyTo.isPresent() && retweetOf.isPresent()) {
      throw new IllegalArgumentException("A tweet cannot be both a reply and a retweet");
    }
    // Existence of the referenced tweet is checked, but its later deletion is not prevented.
    // Enforcing that as an invariant would mean a transaction across two partitions on every
    // post; a reply whose parent has gone is rendered as "this post was deleted", which is
    // what the product wants anyway.
    replyTo.ifPresent(id -> requireExists(id, "replyTo"));
    retweetOf.ifPresent(id -> requireExists(id, "retweetOf"));
  }

  private void requireExists(String tweetId, String field) {
    if (tweets.findById(tweetId).isEmpty()) {
      throw new IllegalArgumentException(field + " refers to a tweet that does not exist");
    }
  }

  /**
   * Fetches one tweet.
   *
   * @param tweetId the tweet
   * @return the tweet, or empty
   */
  public Optional<TweetItem> byId(String tweetId) {
    return tweets.findById(tweetId);
  }

  /**
   * Fetches many tweets, for a timeline render.
   *
   * @param tweetIds the ids
   * @return those that still exist, keyed by id
   */
  public Map<String, TweetItem> byIds(List<String> tweetIds) {
    return tweets.findAllById(tweetIds);
  }

  /**
   * One page of an author's tweets, newest first.
   *
   * @param authorId whose tweets
   * @param after cursor from the previous page
   * @param limit page size
   * @return the page
   */
  public TweetRepository.TweetPage byAuthor(String authorId, Optional<String> after, int limit) {
    return tweets.byAuthor(authorId, after, limit);
  }

  /**
   * Deletes a tweet.
   *
   * @param tweetId the tweet
   * @param authorId who is asking
   * @return true if something was deleted
   */
  public boolean delete(String tweetId, String authorId) {
    // The like rows are deliberately left behind. Deleting them would mean an unbounded
    // partition scan inside a request, and TTL cannot express "when the parent goes"; they
    // are orphaned rows that nothing reads, and a reaper is Phase 12's problem. Recorded in
    // the gap register rather than pretended away.
    return tweets.delete(tweetId, authorId).isPresent();
  }

  /**
   * Likes a tweet.
   *
   * @param tweetId the tweet
   * @param userId who is liking it
   * @return the tweet's like count after the call
   */
  public long like(String tweetId, String userId) {
    if (!likes.like(tweetId, userId)) {
      // Already liked. Returning the current count rather than failing makes the endpoint
      // idempotent, so a retried request is a success and not an error the client must
      // learn to ignore.
      return tweets.findById(tweetId).map(TweetItem::likeCount).orElse(0L);
    }
    return tweets
        .adjustLikeCount(tweetId, 1)
        .orElseGet(
            () -> {
              // The tweet was deleted between the row being written and the counter moving.
              // The like row is now an orphan; leaving it is harmless and undoing it would
              // need the same cross-partition transaction the design avoids everywhere else.
              LOG.debug("Like recorded for deleted tweet {}", tweetId);
              return 0L;
            });
  }

  /**
   * Removes a like.
   *
   * @param tweetId the tweet
   * @param userId who is unliking it
   * @return the tweet's like count after the call
   */
  public long unlike(String tweetId, String userId) {
    if (!likes.unlike(tweetId, userId)) {
      return tweets.findById(tweetId).map(TweetItem::likeCount).orElse(0L);
    }
    // Floored at zero on read rather than guarded on write. A conditional decrement would
    // fail under concurrency and leave the row deleted with the count unchanged, which is
    // worse than a count that is briefly one too low.
    return Math.max(0, tweets.adjustLikeCount(tweetId, -1).orElse(0L));
  }

  /**
   * Whether a user has liked a tweet.
   *
   * @param tweetId the tweet
   * @param userId the user
   * @return true if they have
   */
  public boolean hasLiked(String tweetId, String userId) {
    return likes.hasLiked(tweetId, userId);
  }

  /**
   * Which of these tweets a user has liked.
   *
   * @param tweetIds the tweets on a page
   * @param userId the user
   * @return the subset they have liked
   */
  public Set<String> likedAmong(Collection<String> tweetIds, String userId) {
    return likes.likedAmong(tweetIds, userId);
  }
}
