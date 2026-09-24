/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.persistence;

import dev.starling.contracts.TimelineEntryItem;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

/**
 * Reads one user's materialised timeline.
 *
 * <p>Read-only by design. Every row here was written by fanout-worker, and nothing on the read path
 * is allowed to write: if rendering a timeline could also repair it, a traffic spike on reads would
 * become a write spike on a table already under fan-out load.
 */
@Repository
public class TimelineRepository {

  private final DynamoDbTable<TimelineEntryItem> timelines;

  public TimelineRepository(DynamoDbTable<TimelineEntryItem> timelinesTable) {
    this.timelines = timelinesTable;
  }

  /**
   * One page of a user's materialised timeline, newest first.
   *
   * <p>The cursor is a tweet id rather than an encoded key blob, and that is safe only because the
   * sort key <em>is</em> the tweet id. UUIDv7 makes reverse lexicographic order the same as
   * newest-first, so this needs no timestamp attribute, no secondary index and no sort in memory.
   *
   * @param ownerId whose timeline
   * @param after exclusive start — return entries strictly older than this tweet id
   * @param limit maximum entries to return
   * @return the page, newest first
   */
  public List<TimelineEntryItem> page(String ownerId, Optional<String> after, int limit) {
    Key.Builder start = Key.builder().partitionValue(ownerId);
    QueryConditional conditional =
        after
            .map(cursor -> QueryConditional.sortLessThan(start.sortValue(cursor).build()))
            .orElseGet(
                () -> QueryConditional.keyEqualTo(Key.builder().partitionValue(ownerId).build()));

    return timelines
        .query(
            QueryEnhancedRequest.builder()
                .queryConditional(conditional)
                .scanIndexForward(false)
                .limit(limit)
                .build())
        .stream()
        .findFirst()
        .map(Page::items)
        .orElseGet(List::of);
  }
}
