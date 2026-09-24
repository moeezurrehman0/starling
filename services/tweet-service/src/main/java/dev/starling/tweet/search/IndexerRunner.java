/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.search;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Drives {@link TweetIndexer} in a loop on its own thread.
 *
 * <p>Separated from the indexer so the indexer stays a plain object that does one pass when asked.
 * Everything hard to test — the loop, the sleep, the shutdown flag — lives here, and everything
 * worth testing lives there.
 *
 * <p>A dedicated platform thread rather than a scheduler, and one that runs inside a service whose
 * day job is serving HTTP. A fixed-rate scheduler would start a second pass while the first was
 * still draining, and two passes on the same shard would duplicate work and race on the checkpoint.
 * A platform thread rather than a virtual one because this task is long-lived and mostly blocked on
 * a socket it owns; pinning a carrier thread for the life of the process is exactly what platform
 * threads are for.
 */
@Component
public class IndexerRunner implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(IndexerRunner.class);

  private final TweetIndexer indexer;
  private final SearchProperties properties;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile Thread worker;

  public IndexerRunner(TweetIndexer indexer, SearchProperties properties) {
    this.indexer = indexer;
    this.properties = properties;
  }

  /** Starts the loop once the context is fully up. */
  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (!properties.enabled()) {
      LOG.info("search indexer disabled by configuration");
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    worker = Thread.ofPlatform().name("search-indexer").daemon(false).start(this::loop);
    LOG.info("search indexer started");
  }

  private void loop() {
    while (running.get()) {
      long waitMillis;
      try {
        int processed = indexer.pollOnce();
        waitMillis =
            processed > 0
                ? properties.pollInterval().toMillis()
                : properties.idleBackoff().toMillis();
      } catch (RuntimeException e) {
        // The loop must outlive any single failure. An indexer that dies on a transient
        // Postgres error stops search updating for everybody, while the service it lives in
        // keeps serving reads and looks entirely healthy.
        LOG.error("index poll failed, backing off", e);
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
        // last completed batch, so stopping mid-sleep loses nothing.
        current.interrupt();
      }
      LOG.info("search indexer stopping");
    }
  }
}
