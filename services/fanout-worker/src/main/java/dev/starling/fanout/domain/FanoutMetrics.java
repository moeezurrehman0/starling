/* SPDX-License-Identifier: MIT */
package dev.starling.fanout.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * What the fan-out worker publishes about itself.
 *
 * <p>This service has no HTTP traffic, so the metrics every other service gets for free — {@code
 * http_server_requests_seconds} and its error rate — do not exist here. Without the meters below
 * the only thing Prometheus could see about the most important asynchronous path in the system is a
 * JVM that is alive and a heap that is not full, which is true of a worker that has been doing
 * nothing for an hour.
 *
 * <p>Three things are measured, and the third is the one that matters.
 *
 * <ul>
 *   <li><b>Throughput by outcome.</b> Not a single "records processed" number: a run where every
 *       tweet is {@code CELEBRITY_SKIPPED} and a run where every tweet is {@code FANNED_OUT} are
 *       indistinguishable under one counter and mean completely different things. {@code TRUNCATED}
 *       in particular is a silent correctness failure — followers who will never see the tweet —
 *       and it needs to be countable, not just greppable in a log.
 *   <li><b>Fan-out duration.</b> Per tweet, so a slow author is visible as a tail rather than as a
 *       change in average.
 *   <li><b>Lag.</b> The age of the record when it is processed, taken from the stream's own {@code
 *       ApproximateCreationDateTime} rather than from anything this service records. That makes it
 *       measure the whole pipeline — the write, the stream's propagation, the queue ahead of this
 *       record and this worker's own speed — instead of measuring only the part that is already
 *       fast. It is the SLI: "a tweet appears in a follower's timeline within N seconds" is a
 *       statement about this number and nothing else.
 * </ul>
 *
 * <p>Lag is recorded per record rather than exposed as a gauge of the newest one. A gauge reports
 * the last value scraped and hides the distribution, so a single stuck shard among four averages
 * away; a timer with a histogram lets the alert be written against a quantile, which is the shape
 * the problem actually has.
 */
@Component
public class FanoutMetrics {

  private final MeterRegistry registry;
  private final Timer duration;
  private final Timer lag;
  private final Counter timelineWrites;
  private final Counter malformed;

  /**
   * Registers the meters.
   *
   * @param registry the registry to publish into
   */
  public FanoutMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.duration =
        Timer.builder("fanout.duration")
            .description("Time to materialise one tweet into every timeline that should hold it")
            // Histogram buckets, not just a count and a sum. Without them Prometheus can
            // report the mean and nothing else, and the mean is the one statistic that
            // cannot see the tail this is here to watch.
            .publishPercentileHistogram()
            .register(registry);
    this.lag =
        Timer.builder("fanout.lag")
            .description("Age of a stream record when fan-out finished with it")
            .publishPercentileHistogram()
            // The default ceiling is 30s. Lag during a backlog is minutes, and every value
            // above the ceiling lands in +Inf, which makes the quantile the alert reads
            // unbounded exactly when the alert is supposed to fire.
            .maximumExpectedValue(Duration.ofMinutes(10))
            .register(registry);
    this.timelineWrites =
        Counter.builder("fanout.timeline.writes")
            .description("Timeline entries written, including each author's own copy")
            .register(registry);
    this.malformed =
        Counter.builder("fanout.records.malformed")
            .description("Stream records dropped because they carried no tweet or author id")
            .register(registry);
  }

  /**
   * Times one fan-out and records what it did.
   *
   * @param work the fan-out to run
   * @return the result the work produced
   */
  public FanoutService.Result record(Supplier<FanoutService.Result> work) {
    long started = System.nanoTime();
    FanoutService.Result result = work.get();
    duration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    // Tagged at use rather than pre-registered per outcome: the enum is closed and small, and
    // Micrometer caches the lookup, so the cost is a map hit and the benefit is that adding an
    // Outcome cannot leave a counter silently unregistered.
    Counter.builder("fanout.records")
        .description("Stream records carrying a tweet, by what fan-out decided to do with them")
        .tag("outcome", result.outcome().name().toLowerCase(Locale.ROOT))
        .register(registry)
        .increment();
    timelineWrites.increment(result.timelinesWritten());
    return result;
  }

  /**
   * Records how far behind the stream this record was.
   *
   * @param recordCreatedAt the stream's own timestamp for the record, may be null
   */
  public void recordLag(Instant recordCreatedAt) {
    if (recordCreatedAt == null) {
      // LocalStack does not always populate ApproximateCreationDateTime. Recording zero here
      // would report a perfectly healthy pipeline built entirely out of missing data.
      return;
    }
    Duration age = Duration.between(recordCreatedAt, Instant.now());
    // Clock skew between the stream and this pod can make a fresh record look like it arrived
    // before it was written. Negative durations are rejected by Timer; clamping to zero keeps
    // the sample rather than losing the whole observation.
    lag.record(age.isNegative() ? Duration.ZERO : age);
  }

  /** Records a record that could not be interpreted. */
  public void recordMalformed() {
    malformed.increment();
  }
}
