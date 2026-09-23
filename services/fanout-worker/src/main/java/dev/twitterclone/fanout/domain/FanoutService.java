/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.domain;

import dev.twitterclone.contracts.TimelineEntryItem;
import dev.twitterclone.contracts.UserItem;
import dev.twitterclone.fanout.config.FanoutProperties;
import dev.twitterclone.fanout.persistence.FollowerRepository;
import dev.twitterclone.fanout.persistence.TimelineWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

/**
 * Decides whether a tweet is fanned out, and to whom.
 *
 * <p>The decision is the design. An ordinary author's tweet is written into every follower's
 * timeline so the read is a single query; a celebrity's is written into none, because ten million
 * writes triggered by one post is not a slow path, it is an outage. Celebrity tweets are merged at
 * read time instead, from a cache entry shared by all their followers.
 *
 * <p>Nothing here is transactional and nothing needs to be. The stream delivers at least once, and
 * the write is a plain put on {@code (ownerId, tweetId)}, so a replay rewrites identical rows.
 */
@Service
public class FanoutService {

  private static final Logger LOG = LoggerFactory.getLogger(FanoutService.class);

  /** Followers fetched per query against {@code followee-index}. */
  private static final int FOLLOWER_PAGE = 500;

  private final DynamoDbTable<UserItem> users;
  private final FollowerRepository followers;
  private final TimelineWriter timelines;
  private final FanoutProperties properties;

  public FanoutService(
      DynamoDbTable<UserItem> usersTable,
      FollowerRepository followers,
      TimelineWriter timelines,
      FanoutProperties properties) {
    this.users = usersTable;
    this.followers = followers;
    this.timelines = timelines;
    this.properties = properties;
  }

  /**
   * Materialises one tweet into the timelines that should hold it.
   *
   * @param tweetId the tweet
   * @param authorId who wrote it
   * @return what was done, for metrics and for the caller's logs
   */
  public Result fanOut(String tweetId, String authorId) {
    UserItem author = users.getItem(Key.builder().partitionValue(authorId).build());
    if (author == null) {
      // The account was deleted between the tweet being written and the record being read.
      // Not an error: there is nobody left to fan out on behalf of.
      LOG.debug("author {} no longer exists, skipping tweet {}", authorId, tweetId);
      return new Result(tweetId, Outcome.AUTHOR_GONE, 0);
    }

    long expiresAt = Instant.now().plus(TimelineEntryItem.RETENTION).getEpochSecond();

    // The author's own timeline, always, and before the celebrity check. A celebrity who
    // cannot see their own tweet in their own feed is the most visible possible bug, and it
    // costs exactly one write.
    timelines.write(List.of(entry(authorId, tweetId, authorId, expiresAt)));

    if (author.celebrity()) {
      // The whole point of the hybrid design. Writing this tweet to every follower is the
      // work the system is built to avoid; timeline-service pulls it at read time instead.
      LOG.debug("author {} is a celebrity, tweet {} left for read-time merge", authorId, tweetId);
      return new Result(tweetId, Outcome.CELEBRITY_SKIPPED, 1);
    }

    int written = 1;
    Optional<String> cursor = Optional.empty();
    do {
      FollowerRepository.Page page = followers.followers(authorId, cursor, FOLLOWER_PAGE);
      List<TimelineEntryItem> entries = new ArrayList<>(page.ids().size());
      for (String follower : page.ids()) {
        entries.add(entry(follower, tweetId, authorId, expiresAt));
      }
      written += timelines.write(entries);
      cursor = page.next();

      if (written > properties.maxFollowersPerTweet()) {
        // A backstop for an author who crossed the celebrity threshold without the flag
        // being set -- a follower count that is eventually consistent, a promotion that
        // failed halfway. Stopping here bounds the damage to a truncated fan-out rather
        // than letting one record consume the shard's entire throughput budget.
        LOG.warn(
            "fan-out for tweet {} by {} exceeded {} recipients and was truncated",
            tweetId,
            authorId,
            properties.maxFollowersPerTweet());
        return new Result(tweetId, Outcome.TRUNCATED, written);
      }
    } while (cursor.isPresent());

    return new Result(tweetId, Outcome.FANNED_OUT, written);
  }

  private static TimelineEntryItem entry(
      String owner, String tweetId, String authorId, long expiresAt) {
    return TimelineEntryItem.builder()
        .ownerId(owner)
        .tweetId(tweetId)
        .authorId(authorId)
        .createdAt(Instant.now())
        .expiresAt(expiresAt)
        .build();
  }

  /** What happened to one tweet. */
  public enum Outcome {
    /** Written to every follower's timeline. */
    FANNED_OUT,
    /** Deliberately not fanned out; timeline-service will pull it. */
    CELEBRITY_SKIPPED,
    /** The author no longer exists. */
    AUTHOR_GONE,
    /** Stopped at the safety ceiling. */
    TRUNCATED
  }

  /**
   * The outcome of one fan-out.
   *
   * @param tweetId the tweet
   * @param outcome what was done
   * @param timelinesWritten how many entries were written, including the author's own
   */
  public record Result(String tweetId, Outcome outcome, int timelinesWritten) {}
}
