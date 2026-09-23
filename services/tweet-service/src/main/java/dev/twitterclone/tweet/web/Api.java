/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.web;

import dev.twitterclone.contracts.TweetItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The wire contract.
 *
 * <p>Separate from {@link TweetItem} on purpose. The item record's field names are DynamoDB
 * attribute names chosen to be short, because every one of them is stored on every row; the JSON
 * names are chosen to be readable. Sharing one record would force a choice between a wasteful table
 * and an unreadable API, and would make any future storage change a breaking API change.
 */
public final class Api {

  private Api() {}

  /**
   * A request to post a tweet.
   *
   * @param text the body, which may be empty only for a bare retweet
   * @param mediaKeys keys from upload grants issued to this user
   * @param replyTo the tweet being replied to
   * @param retweetOf the tweet being retweeted
   */
  public record PostTweet(
      @Size(max = 4000) String text,
      @Size(max = 4) List<String> mediaKeys,
      @Nullable String replyTo,
      @Nullable String retweetOf) {

    public PostTweet {
      // Normalised here so neither the validator nor the domain has to special-case null.
      // The real length limit is enforced in code points by the domain; the annotation above
      // is only a cheap upper bound that stops a megabyte body reaching it.
      text = text == null ? "" : text;
      mediaKeys = mediaKeys == null ? List.of() : List.copyOf(mediaKeys);
    }
  }

  /**
   * A tweet as rendered to a client.
   *
   * @param id the tweet id
   * @param authorId who wrote it
   * @param text the body
   * @param mediaKeys object keys, which the client turns into CDN URLs
   * @param replyTo parent tweet, if a reply
   * @param retweetOf source tweet, if a retweet
   * @param createdAt when it was posted
   * @param likeCount how many likes it has
   * @param likedByMe whether the caller has liked it, absent for anonymous reads
   */
  public record Tweet(
      String id,
      String authorId,
      String text,
      List<String> mediaKeys,
      @Nullable String replyTo,
      @Nullable String retweetOf,
      Instant createdAt,
      long likeCount,
      @Nullable Boolean likedByMe) {

    /**
     * Projects a stored item onto the wire.
     *
     * @param item the stored tweet
     * @param likedByMe the caller's like state, or null when unknown
     * @return the response body
     */
    public static Tweet of(TweetItem item, @Nullable Boolean likedByMe) {
      return new Tweet(
          item.tweetId(),
          item.authorId(),
          item.text(),
          item.mediaKeys(),
          item.replyToTweetId(),
          item.retweetOfTweetId(),
          item.createdAt(),
          item.likeCount(),
          likedByMe);
    }
  }

  /**
   * One page of tweets.
   *
   * @param items the tweets, newest first
   * @param nextCursor opaque cursor for the next page, absent at the end
   */
  public record TweetPage(List<Tweet> items, @Nullable String nextCursor) {}

  /**
   * Several tweets fetched by id.
   *
   * @param items those that still exist, keyed by id
   */
  public record TweetBatch(Map<String, Tweet> items) {}

  /**
   * A request for an upload grant.
   *
   * @param contentType the MIME type the client intends to PUT
   * @param contentLength the exact byte count it intends to PUT
   */
  public record UploadRequest(@NotBlank String contentType, @Positive long contentLength) {}

  /**
   * An upload grant.
   *
   * @param key the object key to send back when posting the tweet
   * @param uploadUrl the presigned URL to PUT the bytes to
   * @param expiresAt when the URL stops working
   */
  public record UploadGrant(String key, String uploadUrl, Instant expiresAt) {}

  /**
   * The like state of a tweet.
   *
   * @param likeCount total likes
   * @param likedByMe whether the caller has liked it
   */
  public record LikeState(long likeCount, boolean likedByMe) {}
}
