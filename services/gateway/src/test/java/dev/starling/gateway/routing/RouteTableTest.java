/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.starling.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RouteTable")
class RouteTableTest {

  private static RouteTable table(Map<String, String> routes) {
    return new RouteTable(
        new GatewayProperties(
            routes,
            Duration.ofSeconds(5),
            new GatewayProperties.RateLimit(true, 1, 1, Duration.ofSeconds(1))));
  }

  private static final Map<String, String> ROUTES =
      Map.of(
          "/v1/users", "http://users",
          "/v1/tweets", "http://tweets",
          "/v1/timelines", "http://timeline");

  @Test
  @DisplayName("routes an exact prefix")
  void exact() {
    assertThat(table(ROUTES).upstreamFor("/v1/users")).contains("http://users");
  }

  @Test
  @DisplayName("routes a path below the prefix")
  void nested() {
    assertThat(table(ROUTES).upstreamFor("/v1/tweets/abc/likes")).contains("http://tweets");
  }

  @Test
  @DisplayName("prefers the longest matching prefix")
  void longestWins() {
    Map<String, String> overlapping = new LinkedHashMap<>();
    overlapping.put("/v1/users", "http://users");
    overlapping.put("/v1/users/me/timeline", "http://timeline");

    // With a flat map, iteration order would decide this -- which means hash order would decide
    // it, and the wrong service would answer on some deploys and not others.
    assertThat(table(overlapping).upstreamFor("/v1/users/me/timeline/home"))
        .contains("http://timeline");
  }

  @Test
  @DisplayName("has no opinion about a path nobody owns")
  void unknown() {
    assertThat(table(ROUTES).upstreamFor("/v1/unknown")).isEmpty();
  }

  @Test
  @DisplayName("an empty table routes nothing")
  void empty() {
    assertThat(table(Map.of()).upstreamFor("/v1/users")).isEmpty();
  }

  @Test
  @DisplayName("exposes its prefixes longest first")
  void prefixesAreOrdered() {
    Map<String, String> overlapping = new LinkedHashMap<>();
    overlapping.put("/v1/users", "http://users");
    overlapping.put("/v1/users/me/timeline", "http://timeline");

    assertThat(table(overlapping).prefixes()).containsExactly("/v1/users/me/timeline", "/v1/users");
  }

  @Test
  @DisplayName("null routes are an empty table, not a crash at startup")
  void nullRoutes() {
    assertThat(new RouteTable(new GatewayProperties(null, null, null)).prefixes()).isEmpty();
  }
}
