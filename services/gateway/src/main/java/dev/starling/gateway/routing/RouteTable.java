/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.routing;

import dev.starling.gateway.config.GatewayProperties;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves a request path to the service that owns it.
 *
 * <p>Longest prefix wins. With a flat map the iteration order would decide which of {@code /v1/
 * users} and {@code /v1/users/me/timeline} matched first, making routing depend on hash order — the
 * kind of bug that appears once in production and never reproduces locally.
 */
@Component
public class RouteTable {

  private final List<Map.Entry<String, String>> routes;

  public RouteTable(GatewayProperties properties) {
    this.routes =
        properties.routes().entrySet().stream()
            .sorted(
                Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length())
                    .reversed())
            .toList();
  }

  /**
   * The upstream base URL for a path.
   *
   * @param path the incoming request path
   * @return the upstream, or empty when nothing owns this path
   */
  public Optional<String> upstreamFor(String path) {
    return routes.stream()
        .filter(
            route ->
                path.equals(route.getKey())
                    || path.startsWith(route.getKey() + "/")
                    || path.startsWith(route.getKey()))
        .map(Map.Entry::getValue)
        .findFirst();
  }

  /** The prefixes this gateway knows about, longest first. */
  public List<String> prefixes() {
    return routes.stream().map(Map.Entry::getKey).toList();
  }
}
