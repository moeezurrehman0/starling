/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.starling.contracts.TableSchemas;
import dev.starling.contracts.TimelineEntryItem;
import dev.starling.platform.aws.testing.LocalStack;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The timeline read against a real DynamoDB.
 *
 * <p>The claim being tested is not that the Java is correct but that the <em>table design</em> is:
 * that a descending query on a UUIDv7 sort key returns a timeline page in the right order with no
 * index, no timestamp attribute and no sort in memory. That is a property of DynamoDB's key
 * ordering, and a mock would simply agree with whatever the code assumed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimelinePersistenceIntegrationTest {

  private DynamoDbTable<TimelineEntryItem> table;
  private TimelineRepository timelines;

  @BeforeAll
  void connect() {
    URI endpoint = LocalStack.container().getEndpoint();
    DynamoDbClient client =
        DynamoDbClient.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();
    DynamoDbEnhancedClient enhanced =
        DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    table = enhanced.table("timelines", TableSchemas.TIMELINE_ENTRY);
    timelines = new TimelineRepository(table);
  }

  private void fanOut(String owner, String... tweetIds) {
    for (String tweetId : tweetIds) {
      table.putItem(
          TimelineEntryItem.builder()
              .ownerId(owner)
              .tweetId(tweetId)
              .authorId("author-of-" + tweetId)
              .createdAt(Instant.now())
              .expiresAt(Instant.now().plus(TimelineEntryItem.RETENTION).getEpochSecond())
              .build());
    }
  }

  private static List<String> ids(List<TimelineEntryItem> entries) {
    return entries.stream().map(TimelineEntryItem::tweetId).toList();
  }

  @Nested
  @DisplayName("ordering")
  class Ordering {

    @Test
    @DisplayName("returns entries newest first without any sort attribute")
    void newestFirst() {
      String owner = "order-" + System.nanoTime();
      // Written out of order on purpose: if the order came from insertion rather than from
      // the key, this would come back wrong.
      fanOut(owner, "02", "05", "01", "09");

      assertThat(ids(timelines.page(owner, Optional.empty(), 10)))
          .containsExactly("09", "05", "02", "01");
    }

    @Test
    @DisplayName("one owner's timeline never contains another's entries")
    void partitionsAreIsolated() {
      String mine = "mine-" + System.nanoTime();
      String yours = "yours-" + System.nanoTime();
      fanOut(mine, "01");
      fanOut(yours, "02");

      assertThat(ids(timelines.page(mine, Optional.empty(), 10))).containsExactly("01");
    }
  }

  @Nested
  @DisplayName("paging")
  class Paging {

    @Test
    @DisplayName("honours the limit")
    void limits() {
      String owner = "limit-" + System.nanoTime();
      fanOut(owner, "01", "02", "03", "04");

      assertThat(timelines.page(owner, Optional.empty(), 2)).hasSize(2);
    }

    @Test
    @DisplayName("the cursor is exclusive, so a page never repeats its predecessor's last entry")
    void cursorIsExclusive() {
      String owner = "cursor-" + System.nanoTime();
      fanOut(owner, "01", "02", "03", "04");

      // An inclusive cursor would repeat one tweet on every page boundary, which a client
      // renders as a duplicate rather than as an error.
      assertThat(ids(timelines.page(owner, Optional.of("03"), 10))).containsExactly("02", "01");
    }

    @Test
    @DisplayName("walking with the cursor visits every entry exactly once")
    void walksTheWholeTimeline() {
      String owner = "walk-" + System.nanoTime();
      fanOut(owner, "01", "02", "03", "04", "05");

      List<String> seen = new java.util.ArrayList<>();
      Optional<String> cursor = Optional.empty();
      for (int page = 0; page < 5; page++) {
        List<TimelineEntryItem> entries = timelines.page(owner, cursor, 2);
        if (entries.isEmpty()) {
          break;
        }
        seen.addAll(ids(entries));
        cursor = Optional.of(entries.get(entries.size() - 1).tweetId());
      }

      assertThat(seen).containsExactly("05", "04", "03", "02", "01");
    }

    @Test
    @DisplayName("a cursor past the oldest entry returns nothing rather than wrapping")
    void cursorPastTheEnd() {
      String owner = "end-" + System.nanoTime();
      fanOut(owner, "05");

      assertThat(timelines.page(owner, Optional.of("01"), 10)).isEmpty();
    }
  }

  @Nested
  @DisplayName("empty timelines")
  class Empty {

    @Test
    @DisplayName("a user nothing has been fanned out to gets an empty page, not an error")
    void neverFannedOut() {
      // The common case for a new account, and for any account that follows only
      // celebrities: the partition simply does not exist.
      assertThat(timelines.page("nobody-" + System.nanoTime(), Optional.empty(), 10)).isEmpty();
    }
  }

  @Nested
  @DisplayName("time to live")
  class Ttl {

    @Test
    @DisplayName("entries are written with an expiry in the future")
    void carriesAnExpiry() {
      String owner = "ttl-" + System.nanoTime();
      fanOut(owner, "01");

      // Without this the table grows without bound: materialised timelines are a cache, and
      // the tweets themselves are the durable record.
      assertThat(timelines.page(owner, Optional.empty(), 1).get(0).expiresAt())
          .isGreaterThan(Instant.now().getEpochSecond());
    }
  }
}
