/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports whether a stream consumer has found its stream.
 *
 * <p>Belongs in the <b>readiness</b> group, not liveness. A worker that cannot resolve its stream
 * is not broken — the table may simply not exist yet, or the endpoint may not be reachable yet —
 * and restarting it changes nothing. What must not happen is the pod reporting ready: a ready
 * consumer that consumes nothing is invisible, because every symptom of it is an absence. Fan-out
 * stops, timelines stay empty, and no dashboard turns red.
 *
 * <p>Registering this is a deliberate trade. A worker whose stream never resolves will never become
 * ready, and in a Deployment with a rollout that means the new ReplicaSet never completes and the
 * previous pods keep serving. That is the right outcome: the deploy stalls loudly rather than
 * succeeding into silence.
 *
 * <p>The check is a field read. It performs no I/O, because a health endpoint that calls a
 * dependency lets that dependency's latency decide the probe result, and the probe timeout then
 * decides whether Kubernetes kills the pod.
 */
public class StreamHealthIndicator implements HealthIndicator {

  private final StreamSource source;
  private final boolean consuming;

  /**
   * Creates an indicator for one stream.
   *
   * <p>Registered unconditionally, and told whether the consumer is switched on, rather than only
   * created when it is. A health group lists its members by name and Boot refuses to start when one
   * is missing, so a conditional bean would force {@code validate-group-membership: false} on every
   * service -- trading a typo-proof configuration for a conditional. Reporting {@code UP} with
   * {@code consuming: false} says the same thing and keeps the validation.
   *
   * @param source the stream handle to report on
   * @param consuming whether this process is supposed to be reading the stream at all
   */
  public StreamHealthIndicator(StreamSource source, boolean consuming) {
    this.source = source;
    this.consuming = consuming;
  }

  @Override
  public Health health() {
    boolean ok = !consuming || source.resolved();
    return (ok ? Health.up() : Health.outOfService())
        .withDetail("table", source.tableName())
        .withDetail("consuming", consuming)
        .withDetail("resolved", source.resolved())
        .withDetail("attempts", source.attempts())
        .build();
  }
}
