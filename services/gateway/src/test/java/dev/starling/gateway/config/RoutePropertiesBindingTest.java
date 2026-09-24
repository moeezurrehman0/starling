/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Binds the real application.yaml and checks that the route table survives it.
 *
 * <p>This test exists because of a defect that every other test missed. Spring Boot's relaxed
 * binding treats a map key as a property path, so a key containing a character that cannot appear
 * in one — {@code /}, here — is discarded unless it is written as {@code "[/v1/tweets]"}. The
 * gateway's route map is keyed by path prefix, so without the brackets it bound to an empty map and
 * the edge returned 404 for every route, with nothing in the log to say why.
 *
 * <p>Neither the unit tests for {@code RouteTable} nor the security slice caught it: both construct
 * {@link GatewayProperties} in Java, which bypasses binding entirely, and the security tests assert
 * on 401-versus-not, which a 404 also satisfies. The only thing that finds this class of bug is
 * loading the file that actually ships — so that is what this does, and why it asserts on the
 * concrete prefixes rather than merely that the map is non-empty.
 */
@SpringJUnitConfig
@ContextConfiguration(
    classes = RoutePropertiesBindingTest.Config.class,
    initializers = ConfigDataApplicationContextInitializer.class)
@DisplayName("gateway routes, bound from application.yaml")
class RoutePropertiesBindingTest {

  @EnableConfigurationProperties(GatewayProperties.class)
  static class Config {}

  @Autowired private GatewayProperties properties;

  @Test
  @DisplayName("every declared prefix binds")
  void prefixesBind() {
    assertThat(properties.routes())
        .as("a bare (unbracketed) map key silently binds to nothing")
        .containsOnlyKeys(
            "/v1/jwks",
            "/v1/sessions",
            "/v1/users",
            "/v1/media",
            "/v1/search",
            "/v1/tweets",
            "/v1/timelines");
  }

  @Test
  @DisplayName("each prefix points at the service that serves it")
  void prefixesPointAtTheRightService() {
    assertThat(properties.routes())
        .containsEntry("/v1/jwks", "http://localhost:8081")
        .containsEntry("/v1/sessions", "http://localhost:8081")
        .containsEntry("/v1/users", "http://localhost:8081")
        .containsEntry("/v1/media", "http://localhost:8082")
        .containsEntry("/v1/search", "http://localhost:8082")
        .containsEntry("/v1/tweets", "http://localhost:8082")
        .containsEntry("/v1/timelines", "http://localhost:8083");
  }

  @Test
  @DisplayName("the rest of the block still binds alongside the bracketed keys")
  void siblingsStillBind() {
    // Bracket notation is easy to over-apply; this asserts the neighbouring properties were
    // not collateral damage.
    assertThat(properties.upstreamTimeout()).isNotNull();
    assertThat(properties.rateLimit()).isNotNull();
    assertThat(properties.rateLimit().burst()).isPositive();
  }
}
