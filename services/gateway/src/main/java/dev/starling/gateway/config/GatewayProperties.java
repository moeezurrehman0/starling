/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the edge needs to know about what is behind it.
 *
 * <p>Routes are a map of path prefix to upstream base URL rather than a list of route objects. The
 * gateway does no rewriting, no predicates beyond the prefix and no per-route filters — the moment
 * it does, this becomes a configuration language with no tests, and the routing rules stop being
 * legible from the deployment manifest.
 *
 * @param routes path prefix to upstream base URL
 * @param upstreamTimeout how long to wait on an upstream before giving up
 * @param rateLimit the token bucket applied to every authenticated caller
 */
@ConfigurationProperties(prefix = "starling.gateway")
public record GatewayProperties(
    Map<String, String> routes, Duration upstreamTimeout, RateLimit rateLimit) {

  public GatewayProperties {
    routes = routes == null ? Map.of() : Map.copyOf(routes);
    upstreamTimeout = upstreamTimeout == null ? Duration.ofSeconds(5) : upstreamTimeout;
    rateLimit =
        rateLimit == null ? new RateLimit(true, 100, 100, Duration.ofSeconds(1)) : rateLimit;
  }

  /**
   * A token bucket.
   *
   * <p>Burst and refill are separate because they answer different questions. Burst is how much
   * work one caller may do at once — a client opening a timeline fires several requests in the same
   * breath, and a bucket sized to the steady rate would reject its own first page. Refill is the
   * rate the system is willing to sustain.
   *
   * @param enabled whether to enforce at all
   * @param burst bucket capacity
   * @param refill tokens added per period
   * @param period how often the refill happens
   */
  public record RateLimit(boolean enabled, int burst, int refill, Duration period) {}
}
