/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the UUIDv7 generator.
 *
 * <p>The property under test is not really "produces a UUID" — it is "sorts by creation time as a
 * string". Every one of these assertions exists because breaking that property breaks the timeline
 * merge in a different service, without any error surfacing anywhere.
 */
@DisplayName("Ids")
class IdsTest {

  @Test
  @DisplayName("mints version 7, RFC 4122 variant UUIDs")
  void versionAndVariant() {
    UUID id = Ids.newUuid();
    assertThat(id.version()).isEqualTo(7);
    // Variant 2 is the java.util.UUID spelling of the RFC 4122 10xx bit pattern. Getting this
    // wrong produces a UUID that parses but that other tooling will reject as malformed.
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  @DisplayName("embeds the current time in the leading bits")
  void embedsTime() {
    long before = System.currentTimeMillis();
    UUID id = Ids.newUuid();
    long after = System.currentTimeMillis();

    assertThat(Ids.timestampOf(id)).isBetween(before, after + 1);
  }

  @Test
  @DisplayName("sorts lexicographically in creation order, which is what the merge relies on")
  void lexicographicOrderMatchesCreationOrder() {
    // The critical property. timeline-service merges materialised entries with celebrity
    // tweets by comparing id strings and nothing else; if this ever fails, that merge silently
    // returns a wrongly ordered timeline and keyset pagination starts skipping tweets.
    List<String> minted = IntStream.range(0, 5_000).mapToObj(i -> Ids.newId()).toList();
    assertThat(minted).isSorted();
  }

  @Test
  @DisplayName("stays strictly increasing through a burst inside a single millisecond")
  void monotonicWithinAMillisecond() {
    // 5000 ids is more than the 4096 slots the 12-bit counter provides, so this also exercises
    // the borrow-from-the-next-millisecond path. Filling those bits randomly, as most UUIDv7
    // libraries do, would make this fail roughly always.
    List<String> minted = IntStream.range(0, 5_000).mapToObj(i -> Ids.newId()).toList();
    assertThat(minted).doesNotHaveDuplicates();
    assertThat(minted).isEqualTo(minted.stream().sorted().toList());
  }

  @Test
  @DisplayName("mints unique ids under concurrency")
  void uniqueUnderConcurrency() throws Exception {
    int threads = 16;
    int perThread = 2_000;

    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<List<String>>> tasks =
          IntStream.range(0, threads)
              .<Callable<List<String>>>mapToObj(
                  t -> () -> IntStream.range(0, perThread).mapToObj(i -> Ids.newId()).toList())
              .toList();

      Set<String> all =
          pool.invokeAll(tasks).stream()
              .map(IdsTest::join)
              .flatMap(List::stream)
              .collect(java.util.stream.Collectors.toSet());

      // A CAS loop over the (millisecond, counter) pair is the whole defence here. Holding the
      // timestamp and counter in two separate atomics would let a rollover interleave and
      // reissue a counter value, which shows up only as a duplicate under real concurrency.
      assertThat(all).hasSize(threads * perThread);
    }
  }

  private static <T> T join(Future<T> future) {
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName("refuses to read a timestamp out of a v4, rather than inventing one")
  void rejectsNonV7() {
    // Reading the leading bits of a random UUID as a timestamp yields a plausible date tens of
    // thousands of years away, which looks like data rather than like an error.
    assertThatThrownBy(() -> Ids.timestampOf(UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Not a UUIDv7");
  }

  @Test
  @DisplayName("renders in the canonical 36-character form")
  void canonicalForm() {
    // Ids are stored as DynamoDB strings and appear in URLs, so the textual form is itself
    // part of the contract: switching to a compact base64 spelling would change every key.
    String id = Ids.newId();
    assertThat(id)
        .hasSize(36)
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }
}
