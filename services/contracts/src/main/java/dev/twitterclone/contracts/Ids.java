/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generates the UUIDv7 identifiers used for every user, tweet and timeline entry.
 *
 * <h2>Why this is a contract rather than a utility</h2>
 *
 * <p>This module otherwise holds declarations only, and a generator is behaviour. It is here
 * anyway, because the <em>ordering property</em> of these ids is depended upon across service
 * boundaries and is exactly the kind of agreement this module exists to prevent breaking.
 *
 * <p>{@code tweet-service} mints a tweet id. {@code timelines} then uses that id as its sort key,
 * and {@code timeline-service} merges materialised entries with celebrity tweets fetched at read
 * time by comparing ids lexicographically and nothing else. That merge is only correct because
 * UUIDv7 puts a big-endian millisecond timestamp in its leading 48 bits, so string order equals
 * creation order.
 *
 * <p>If one service were to generate a {@link UUID#randomUUID() v4} instead, nothing would fail. No
 * exception, no log line, no failing test in that service. Timelines would simply start coming back
 * in an order that is subtly wrong, and keyset pagination over them would begin skipping and
 * repeating tweets. Duplicating this code per service is an invitation to exactly that, so it is
 * written once.
 *
 * <p>The rule for this module is therefore not "no behaviour" but "no I/O, no configuration, no
 * Spring, and behaviour only where the behaviour itself is the cross-service agreement".
 *
 * <h2>Monotonicity within a millisecond</h2>
 *
 * <p>RFC 9562 leaves the 12 bits after the timestamp free. Filling them randomly is legal and is
 * what most libraries do, but it means two ids minted in the same millisecond sort in random order
 * relative to one another. That is tolerable for a primary key and not tolerable for a pagination
 * cursor: a reader paging a busy timeline with {@code after=<id>} would skip any same-millisecond
 * sibling that happened to sort lower.
 *
 * <p>So this uses the counter method from RFC 9562 §6.2: the 12 bits hold a sequence that resets
 * each millisecond. Ids from this JVM are then strictly increasing. Across JVMs they are not, and
 * cannot be without coordination — but the clash window is one millisecond and the consequence is a
 * stable-but-arbitrary order between two near-simultaneous tweets from different pods, which is not
 * a correctness problem for any reader.
 *
 * <h2>What it does not attempt</h2>
 *
 * <p>Nothing here defends against the wall clock moving backwards. An NTP step or a VM migration
 * can produce an id that sorts before its predecessor. Guarding that properly means refusing to
 * mint ids until the clock catches up, which trades a subtle ordering anomaly for an outage; for a
 * social timeline that is the wrong trade, and the anomaly is recorded here instead of defended
 * against.
 */
public final class Ids {

  private Ids() {}

  /** Version nibble for UUIDv7, positioned in the high 4 bits of the seventh byte. */
  private static final long VERSION_7 = 0x7000L;

  /** RFC 4122 variant bits, positioned in the high 2 bits of the ninth byte. */
  private static final long VARIANT_RFC4122 = 0x8000_0000_0000_0000L;

  /** Largest value the 12-bit intra-millisecond counter can hold. */
  private static final int COUNTER_MAX = 0x0FFF;

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * Last observed millisecond and the counter within it.
   *
   * <p>Held as one immutable pair behind a CAS loop rather than as two separate atomics, because
   * the two values must advance together: a thread that read the timestamp and the counter in
   * separate operations could interleave with a millisecond rollover and reuse a counter value,
   * producing two identical ids.
   */
  private record Tick(long epochMilli, int counter) {}

  private static final AtomicReference<Tick> LAST = new AtomicReference<>(new Tick(0L, 0));

  /** Returns a new time-ordered identifier in the canonical 36-character form. */
  public static String newId() {
    return newUuid().toString();
  }

  /** Returns a new time-ordered {@link UUID}, version 7. */
  public static UUID newUuid() {
    Tick tick = nextTick(System.currentTimeMillis());

    long high = (tick.epochMilli() << 16) | VERSION_7 | tick.counter();
    long low = (RANDOM.nextLong() >>> 2) | VARIANT_RFC4122;

    return new UUID(high, low);
  }

  private static Tick nextTick(long now) {
    while (true) {
      Tick previous = LAST.get();
      Tick next = advance(previous, now);
      if (LAST.compareAndSet(previous, next)) {
        return next;
      }
    }
  }

  private static Tick advance(Tick previous, long now) {
    if (now > previous.epochMilli()) {
      return new Tick(now, 0);
    }
    if (previous.counter() < COUNTER_MAX) {
      // Same millisecond, or the clock went backwards: keep the previous timestamp so that
      // ids never regress, and take the next counter slot.
      return new Tick(previous.epochMilli(), previous.counter() + 1);
    }
    // More than 4096 ids in one millisecond. Borrowing from the next millisecond keeps them
    // strictly increasing and unique; the timestamp drifts ahead of the clock by however long
    // the burst lasts, which is far less harmful than either blocking or emitting a duplicate.
    return new Tick(previous.epochMilli() + 1, 0);
  }

  /**
   * Extracts the embedded creation time, in epoch milliseconds.
   *
   * <p>Useful for debugging and for the stream consumers, which can tell how far behind they are
   * from the id alone without reading a timestamp attribute.
   *
   * @throws IllegalArgumentException if the id is not a version 7 UUID, because reading the leading
   *     bits of a v4 as a timestamp yields a plausible-looking date somewhere in the far future
   *     rather than an obvious error
   */
  public static long timestampOf(UUID id) {
    if (id.version() != 7) {
      throw new IllegalArgumentException("Not a UUIDv7: " + id + " (version " + id.version() + ")");
    }
    return id.getMostSignificantBits() >>> 16;
  }
}
