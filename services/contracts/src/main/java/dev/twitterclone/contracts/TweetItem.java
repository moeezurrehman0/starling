/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A row of the {@code tweets} table: one post, reply or retweet.
 *
 * <p>Partitioned on {@code tweetId} alone rather than on {@code (authorId, tweetId)}. A composite
 * key would make an author's timeline a single cheap query, but it would make fetching one tweet by
 * its id impossible without also knowing who wrote it — and a permalink, a reply reference and a
 * retweet reference all carry only the id. The author's own timeline is served instead by the
 * {@code author-index} GSI, which is the query that tolerates eventual consistency; a permalink is
 * not.
 *
 * <p>{@code tweetId} is a UUIDv7, so its lexicographic order is its creation order. That is what
 * makes a timeline merge possible without a sort key on time and without reading {@code createdAt}
 * at all: the fan-out worker and the read-path merge both order by id.
 *
 * <p><strong>This table carries the stream.</strong> Its DynamoDB Stream is the project's only
 * event transport (ADR-0012), read by {@code fanout-worker} and by the search indexer. Any change
 * to the attributes here changes the event payload those two consume, which is why this record
 * lives in a shared module and why attribute renames must follow expand–contract.
 */
public record TweetItem(
    String tweetId,
    String authorId,
    String text,
    List<String> mediaKeys,
    @Nullable String replyToTweetId,
    @Nullable String retweetOfTweetId,
    Instant createdAt,
    long likeCount) {

  /** Name of the GSI that answers "the tweets written by this author, newest first". */
  public static final String AUTHOR_INDEX = "author-index";

  /** Longest permitted body, in Unicode code points. */
  public static final int MAX_TEXT_LENGTH = 280;

  public TweetItem {
    mediaKeys = List.copyOf(mediaKeys);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String tweetId;
    private @Nullable String authorId;
    private @Nullable String text;
    private List<String> mediaKeys = List.of();
    private @Nullable String replyToTweetId;
    private @Nullable String retweetOfTweetId;
    private @Nullable Instant createdAt;
    private long likeCount;

    public Builder tweetId(String value) {
      this.tweetId = value;
      return this;
    }

    public Builder authorId(String value) {
      this.authorId = value;
      return this;
    }

    public Builder text(String value) {
      this.text = value;
      return this;
    }

    /** Null-tolerant: DynamoDB stores no attribute at all for an empty list. */
    public Builder mediaKeys(@Nullable List<String> value) {
      this.mediaKeys = value == null ? List.of() : List.copyOf(value);
      return this;
    }

    public Builder replyToTweetId(@Nullable String value) {
      this.replyToTweetId = value;
      return this;
    }

    public Builder retweetOfTweetId(@Nullable String value) {
      this.retweetOfTweetId = value;
      return this;
    }

    public Builder createdAt(Instant value) {
      this.createdAt = value;
      return this;
    }

    public Builder likeCount(long value) {
      this.likeCount = value;
      return this;
    }

    public TweetItem build() {
      return new TweetItem(
          Contracts.required(tweetId, "tweetId"),
          Contracts.required(authorId, "authorId"),
          Contracts.required(text, "text"),
          mediaKeys,
          replyToTweetId,
          retweetOfTweetId,
          Contracts.required(createdAt, "createdAt"),
          likeCount);
    }
  }
}
