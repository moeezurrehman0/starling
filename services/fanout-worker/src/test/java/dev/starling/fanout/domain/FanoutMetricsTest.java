/* SPDX-License-Identifier: MIT */
package dev.starling.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What the worker publishes about itself.
 *
 * <p>Metrics are usually left untested on the grounds that they are only numbers. The grounds are
 * wrong in both directions: a meter that is never registered is a dashboard panel that reads "No
 * data" and an alert that can never fire, and both look exactly like a healthy system.
 */
class FanoutMetricsTest {

  private final MeterRegistry registry = new SimpleMeterRegistry();
  private final FanoutMetrics metrics = new FanoutMetrics(registry);

  @Test
  void countsEachOutcomeSeparately() {
    metrics.record(() -> new FanoutService.Result("t1", FanoutService.Outcome.FANNED_OUT, 12));
    metrics.record(() -> new FanoutService.Result("t2", FanoutService.Outcome.FANNED_OUT, 3));
    metrics.record(
        () -> new FanoutService.Result("t3", FanoutService.Outcome.CELEBRITY_SKIPPED, 1));

    assertThat(registry.get("fanout.records").tag("outcome", "fanned_out").counter().count())
        .isEqualTo(2);
    assertThat(registry.get("fanout.records").tag("outcome", "celebrity_skipped").counter().count())
        .isEqualTo(1);
  }

  @Test
  void truncationIsCountable() {
    // The whole reason the outcome is a tag. A truncated fan-out is followers who will never
    // see the tweet, and the only other record of it is a WARN nobody is watching for.
    metrics.record(() -> new FanoutService.Result("t1", FanoutService.Outcome.TRUNCATED, 50_001));

    assertThat(registry.get("fanout.records").tag("outcome", "truncated").counter().count())
        .isEqualTo(1);
  }

  @Test
  void sumsTimelineWrites() {
    metrics.record(() -> new FanoutService.Result("t1", FanoutService.Outcome.FANNED_OUT, 12));
    metrics.record(() -> new FanoutService.Result("t2", FanoutService.Outcome.FANNED_OUT, 30));

    assertThat(registry.get("fanout.timeline.writes").counter().count()).isEqualTo(42);
  }

  @Test
  void timesTheWorkAndReturnsItsResult() {
    FanoutService.Result result =
        metrics.record(() -> new FanoutService.Result("t1", FanoutService.Outcome.FANNED_OUT, 1));

    assertThat(result.tweetId()).isEqualTo("t1");
    assertThat(registry.get("fanout.duration").timer().count()).isEqualTo(1);
  }

  @Test
  void lagIsMeasuredFromTheRecordsOwnTimestamp() {
    metrics.recordLag(Instant.now().minus(Duration.ofSeconds(30)));

    assertThat(registry.get("fanout.lag").timer().totalTime(TimeUnit.SECONDS))
        .isBetween(29.0, 40.0);
  }

  @Test
  void aMissingTimestampRecordsNothingRatherThanZero() {
    // LocalStack does not always populate ApproximateCreationDateTime. Recording zero would
    // produce a lag distribution built out of absent data, reading perfectly healthy.
    metrics.recordLag(null);

    assertThat(registry.get("fanout.lag").timer().count()).isZero();
  }

  @Test
  void clockSkewIsClampedRatherThanDropped() {
    // A record from the near future is skew between DynamoDB and this pod, not a real
    // negative age. Timer rejects negatives, so the choice is clamp or lose the observation.
    metrics.recordLag(Instant.now().plus(Duration.ofSeconds(5)));

    assertThat(registry.get("fanout.lag").timer().count()).isEqualTo(1);
    assertThat(registry.get("fanout.lag").timer().totalTime(TimeUnit.SECONDS)).isZero();
  }

  @Test
  void lagHistogramReachesPastThirtySeconds() {
    // The default Timer ceiling is 30s. A backlog is minutes, and if every value above the
    // ceiling collapses into +Inf then the quantile the alert reads goes unbounded at exactly
    // the moment the alert is supposed to distinguish "slow" from "stuck".
    //
    // Asserted against a Prometheus registry rather than the simple one used above, because
    // SimpleMeterRegistry does not materialise histogram buckets at all: the same meter that
    // scrapes correctly in production would show an empty snapshot here, and the test would
    // be measuring the test registry instead of the configuration.
    PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    new FanoutMetrics(prometheus).recordLag(Instant.now().minus(Duration.ofMinutes(4)));

    boolean hasFiniteBucketAboveThirtySeconds =
        Arrays.stream(prometheus.get("fanout.lag").timer().takeSnapshot().histogramCounts())
            .anyMatch(b -> Double.isFinite(b.bucket()) && b.bucket(TimeUnit.SECONDS) > 30);
    assertThat(hasFiniteBucketAboveThirtySeconds).isTrue();
  }

  @Test
  void malformedRecordsAreCounted() {
    metrics.recordMalformed();

    assertThat(registry.get("fanout.records.malformed").counter().count()).isEqualTo(1);
  }
}
