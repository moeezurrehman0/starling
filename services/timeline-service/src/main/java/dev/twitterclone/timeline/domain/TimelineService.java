/* SPDX-License-Identifier: MIT */
package dev.twitterclone.timeline.domain;

import dev.twitterclone.contracts.TimelineEntryItem;
import dev.twitterclone.timeline.persistence.TimelineRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The hybrid read: materialised entries merged with celebrity tweets pulled live.
 *
 * <p>Neither pure strategy works at both ends of the follower distribution. Fan-out on write alone
 * means a single post by an account with ten million followers becomes ten million writes, which is
 * both slow and, on a sandbox account, immediately fatal. Fan-out on read alone means every
 * timeline render scans every followee's recent tweets, which is fine for a user following twelve
 * accounts and hopeless at scale.
 *
 * <p>So the split is by author, not by reader. Ordinary authors are fanned out on write and their
 * tweets are already sitting in {@code timelines} when the reader arrives. Celebrity authors are
 * pulled at read time from a cache entry shared by all their followers. The merge happens here.
 *
 * <p>What makes the merge cheap is that tweet ids are UUIDv7. Both halves are already sorted
 * newest-first by id, ids are globally comparable as strings, and "before the cursor" is a string
 * comparison. No timestamps are consulted and no clock is trusted.
 */
@Service
public class TimelineService {

  private final TimelineRepository timelines;
  private final FollowGraphClient followGraph;
  private final TweetFeedClient tweetFeed;

  public TimelineService(
      TimelineRepository timelines, FollowGraphClient followGraph, TweetFeedClient tweetFeed) {
    this.timelines = timelines;
    this.followGraph = followGraph;
    this.tweetFeed = tweetFeed;
  }

  /**
   * One page of a user's home timeline.
   *
   * @param userId the reader
   * @param after exclusive cursor — return tweets strictly older than this id
   * @param limit page size
   * @return the page, newest first
   */
  public Timeline home(String userId, Optional<String> after, int limit) {
    // Both halves are over-read by a page, not read exactly. Either source can contribute
    // every entry in the final page, and either can contribute none, so asking each for
    // exactly `limit` and merging would sometimes return a short page while more entries
    // existed -- which a client cannot distinguish from the end of the timeline.
    Set<String> candidates = new LinkedHashSet<>();
    for (TimelineEntryItem entry : timelines.page(userId, after, limit)) {
      candidates.add(entry.tweetId());
    }
    for (String celebrity : followGraph.celebrityFollowees(userId)) {
      for (TweetView tweet : tweetFeed.recentByAuthor(celebrity)) {
        if (after.isEmpty() || tweet.id().compareTo(after.get()) < 0) {
          candidates.add(tweet.id());
        }
      }
    }

    List<String> ordered =
        candidates.stream().sorted(Comparator.reverseOrder()).limit(limit).toList();
    Map<String, TweetView> bodies = tweetFeed.hydrate(ordered);

    List<TweetView> page = new ArrayList<>(ordered.size());
    for (String id : ordered) {
      // Dropped rather than rendered as a placeholder: an id with no body is a tweet deleted
      // after fan-out, and the reader should see it gone, not see a tombstone.
      TweetView tweet = bodies.get(id);
      if (tweet != null) {
        page.add(tweet);
      }
    }

    // The cursor is the last *candidate* id, not the last surviving body. Using the body would
    // mean a page whose tail was entirely deleted tweets hands back the cursor it was given,
    // and the client loops forever asking for the same page.
    Optional<String> next =
        ordered.size() < limit ? Optional.empty() : Optional.of(ordered.get(ordered.size() - 1));
    return new Timeline(List.copyOf(page), next);
  }

  /**
   * A rendered page.
   *
   * @param tweets the tweets, newest first
   * @param next cursor for the following page, absent at the end
   */
  public record Timeline(List<TweetView> tweets, Optional<String> next) {}
}
