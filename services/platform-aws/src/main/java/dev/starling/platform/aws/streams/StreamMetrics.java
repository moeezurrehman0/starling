/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * Publishes whether a stream consumer has found its stream.
 *
 * <p>The companion to {@link StreamHealthIndicator}, and deliberately not the same thing. The probe
 * answers "should this pod receive traffic, right now"; these gauges answer "how long has this been
 * true, across the fleet, and did it coincide with the deploy". A readiness probe leaves no history
 * — by the time anyone looks, the pod has either recovered or been replaced, and the only evidence
 * is a restart count with no story attached.
 *
 * <p>Both are needed because they fail differently. If the probe is wrong the pod serves when it
 * should not; if the gauge is missing the incident has no timeline. The condition this reports —
 * unresolved stream — is exactly the one from gap register row 18, which was invisible precisely
 * because nothing emitted a number.
 *
 * <p>{@code stream_resolve_attempts_total} is a gauge rather than a counter even though it only
 * increases, because the value is owned by {@link StreamSource} rather than by the registry. A
 * counter would require every retry to call back into Micrometer; a gauge samples the number that
 * already exists. Its rate is the useful signal: flat at zero means resolved, climbing means
 * retrying.
 */
public final class StreamMetrics implements MeterBinder {

  private final StreamSource source;
  private final String group;

  /**
   * Creates the binder.
   *
   * @param source the stream handle to sample
   * @param group the consumer group, so two consumers of the same table stay distinguishable
   */
  public StreamMetrics(StreamSource source, String group) {
    this.source = source;
    this.group = group;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("stream.resolved", source, s -> s.resolved() ? 1 : 0)
        .description("1 when the consumer has discovered its stream ARN, 0 while it is retrying")
        .tag("table", source.tableName())
        .tag("group", group)
        .register(registry);

    Gauge.builder("stream.resolve.attempts", source, StreamSource::attempts)
        .description("Discovery attempts made; stops increasing once the stream is found")
        .tag("table", source.tableName())
        .tag("group", group)
        .register(registry);
  }
}
