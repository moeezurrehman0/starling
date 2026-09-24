/* SPDX-License-Identifier: MIT */
package dev.starling.fanout.domain;

import dev.starling.fanout.config.FanoutProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Drives {@link StreamConsumer} in a loop on its own thread.
 *
 * <p>Separated from the consumer so the consumer stays a plain object that does one pass when
 * asked. Everything hard to test — the loop, the sleep, the shutdown flag — lives here, and
 * everything worth testing lives there.
 *
 * <p>A dedicated platform thread rather than a scheduler. This is one long-lived task that should
 * never overlap itself; a fixed-rate scheduler would start a second pass while the first was still
 * draining a large fan-out, and two passes on the same shard would duplicate every write and race
 * on the checkpoint.
 */
@Component
public class FanoutRunner implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(FanoutRunner.class);

  private final StreamConsumer consumer;
  private final FanoutProperties properties;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile Thread worker;

  public FanoutRunner(StreamConsumer consumer, FanoutProperties properties) {
    this.consumer = consumer;
    this.properties = properties;
  }

  /** Starts the loop once the context is fully up. */
  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (!properties.enabled()) {
      LOG.info("fan-out consumer disabled by configuration");
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    worker = Thread.ofPlatform().name("fanout-consumer").daemon(false).start(this::loop);
    LOG.info("fan-out consumer started");
  }

  private void loop() {
    while (running.get()) {
      long waitMillis;
      try {
        int processed = consumer.pollOnce();
        // Back off harder when there was nothing to read. Polling an idle stream at the
        // busy interval costs a GetRecords call per shard per tick for no records, and
        // DynamoDB Streams bills and throttles those the same as productive ones.
        waitMillis =
            processed > 0
                ? properties.pollInterval().toMillis()
                : properties.idleBackoff().toMillis();
      } catch (RuntimeException e) {
        // The loop must outlive any single failure. A consumer that dies on a transient
        // error stops fan-out for everybody until a human notices the pod is up but idle.
        LOG.error("poll failed, backing off", e);
        waitMillis = properties.idleBackoff().toMillis();
      }
      if (!sleep(waitMillis)) {
        return;
      }
    }
  }

  private boolean sleep(long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Override
  public void close() {
    if (running.compareAndSet(true, false)) {
      Thread current = worker;
      if (current != null) {
        // Interrupt rather than wait out the backoff. The checkpoint was written after the
        // last completed batch, so stopping mid-sleep loses nothing -- the next start
        // resumes from there.
        current.interrupt();
      }
      LOG.info("fan-out consumer stopping");
    }
  }
}
