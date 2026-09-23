/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IndexerRunner")
class IndexerRunnerTest {

  private static SearchProperties properties(boolean enabled) {
    return new SearchProperties(
        "arn:stream", Duration.ofMillis(5), Duration.ofMillis(5), 100, enabled);
  }

  @Mock private TweetIndexer indexer;

  /** Spins until the condition holds, so the test does not depend on a fixed sleep. */
  private static void eventually(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("condition never held within 5s");
  }

  @Test
  @DisplayName("polls repeatedly once the context is ready")
  void pollsInALoop() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    when(indexer.pollOnce()).thenAnswer(invocation -> calls.incrementAndGet());

    try (IndexerRunner runner = new IndexerRunner(indexer, properties(true))) {
      runner.start();
      eventually(() -> calls.get() >= 3);
    }
  }

  @Test
  @DisplayName("stays down when disabled, which is the default")
  void disabled() throws Exception {
    try (IndexerRunner runner = new IndexerRunner(indexer, properties(false))) {
      runner.start();
      Thread.sleep(50);
    }

    verify(indexer, never()).pollOnce();
  }

  @Test
  @DisplayName("survives a failing poll rather than dying silently")
  void keepsGoingAfterAFailure() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    when(indexer.pollOnce())
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("postgres is down");
              }
              return 0;
            });

    try (IndexerRunner runner = new IndexerRunner(indexer, properties(true))) {
      runner.start();
      eventually(() -> calls.get() >= 3);
    }
  }

  @Test
  @DisplayName("starting twice does not start a second thread")
  void startIsIdempotent() throws Exception {
    when(indexer.pollOnce()).thenReturn(0);

    try (IndexerRunner runner = new IndexerRunner(indexer, properties(true))) {
      runner.start();
      runner.start();
      Thread.sleep(50);
    }

    assertThat(
            Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "search-indexer".equals(t.getName()))
                .count())
        .isLessThanOrEqualTo(1);
  }

  @Test
  @DisplayName("closing an unstarted runner is harmless")
  void closeWithoutStart() {
    new IndexerRunner(indexer, properties(true)).close();

    verify(indexer, never()).pollOnce();
  }
}
