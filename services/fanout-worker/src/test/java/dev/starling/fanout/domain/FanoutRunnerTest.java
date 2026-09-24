/* SPDX-License-Identifier: MIT */
package dev.starling.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.starling.fanout.config.FanoutProperties;
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
@DisplayName("FanoutRunner")
class FanoutRunnerTest {

  private static FanoutProperties properties(boolean enabled) {
    return new FanoutProperties(
        "arn:stream", Duration.ofMillis(5), Duration.ofMillis(5), 100, 50_000, enabled);
  }

  @Mock private StreamConsumer consumer;

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
    when(consumer.pollOnce()).thenAnswer(invocation -> calls.incrementAndGet());

    try (FanoutRunner runner = new FanoutRunner(consumer, properties(true))) {
      runner.start();
      eventually(() -> calls.get() >= 3);
    }
  }

  @Test
  @DisplayName("stays down when disabled, without starting a thread")
  void disabled() throws Exception {
    // The switch exists so the image can be deployed and observed before it is allowed to
    // write to everyone's timeline -- and so a runaway consumer can be stopped by config
    // rather than by deleting the deployment.
    try (FanoutRunner runner = new FanoutRunner(consumer, properties(false))) {
      runner.start();
      Thread.sleep(50);
      verify(consumer, never()).pollOnce();
    }
  }

  @Test
  @DisplayName("keeps polling after a pass throws")
  void survivesFailures() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    when(consumer.pollOnce())
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("transient");
              }
              return 0;
            });

    // A consumer that dies on the first throttle leaves the pod up and healthy-looking while
    // fan-out silently stops for everybody.
    try (FanoutRunner runner = new FanoutRunner(consumer, properties(true))) {
      runner.start();
      eventually(() -> calls.get() >= 3);
    }
  }

  @Test
  @DisplayName("starting twice does not start two loops")
  void startIsIdempotent() throws Exception {
    when(consumer.pollOnce()).thenReturn(0);

    try (FanoutRunner runner = new FanoutRunner(consumer, properties(true))) {
      runner.start();
      runner.start();
      assertThat(
              Thread.getAllStackTraces().keySet().stream()
                  .filter(t -> "fanout-consumer".equals(t.getName()))
                  .count())
          .isEqualTo(1);
    }
  }

  @Test
  @DisplayName("close stops the loop")
  void closeStops() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    when(consumer.pollOnce()).thenAnswer(invocation -> calls.incrementAndGet());

    FanoutRunner runner = new FanoutRunner(consumer, properties(true));
    runner.start();
    eventually(() -> calls.get() >= 1);
    runner.close();

    // Read the count after the loop has had time to unwind, not immediately. close() can
    // land while a pass is already in flight, so the count legitimately rises once more
    // afterwards; sampling straight away makes this test fail roughly one run in twenty for
    // a reason that has nothing to do with what it is checking.
    Thread.sleep(200);
    int seen = calls.get();
    Thread.sleep(200);
    assertThat(calls.get()).isEqualTo(seen);
  }
}
