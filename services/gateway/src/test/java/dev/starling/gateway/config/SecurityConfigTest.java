/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.starling.gateway.ratelimit.TokenBucket;
import dev.starling.gateway.routing.ProxyController;
import dev.starling.gateway.routing.RouteTable;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * What the edge lets through without a token.
 *
 * <p>The route table is stubbed to know nothing, so every request that survives the security chain
 * ends at the proxy's 404. That makes the assertion unambiguous: 401 means the filter chain
 * refused, 404 means it did not.
 */
@WebMvcTest(controllers = ProxyController.class)
@Import(SecurityConfig.class)
@DisplayName("gateway security policy")
class SecurityConfigTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private RestClient upstreamClient;
  @MockitoBean private RouteTable routes;
  @MockitoBean private TokenBucket bucket;

  @BeforeEach
  void noRoutes() {
    Mockito.when(routes.upstreamFor(Mockito.anyString())).thenReturn(Optional.empty());
    Mockito.when(bucket.spend(Mockito.anyString())).thenReturn(new TokenBucket.Decision(true, 1));
    Mockito.when(bucket.period()).thenReturn(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("log-in does not require a token")
  void loginIsOpen() throws Exception {
    // It is how a token is obtained. Requiring one would be a closed loop.
    mvc.perform(post("/v1/sessions")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("sign-up does not require a token")
  void registrationIsOpen() throws Exception {
    // A new account has no credential yet, so the create call cannot demand one.
    mvc.perform(post("/v1/users")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("the JWKS does not require a token")
  void jwksIsOpen() throws Exception {
    // It is how a token is verified, including by this gateway itself.
    mvc.perform(get("/v1/jwks")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("reading a profile without an account is allowed")
  void anonymousReadsAreAllowed() throws Exception {
    // A logged-out visitor reading a public profile is the product working, not a hole.
    mvc.perform(get("/v1/users/alice")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("reading a tweet without an account is allowed")
  void anonymousTweetReads() throws Exception {
    mvc.perform(get("/v1/tweets/t1")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("searching without an account is allowed")
  void anonymousSearch() throws Exception {
    mvc.perform(get("/v1/search/tweets")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("posting a tweet without a token is refused")
  void writesRequireAToken() throws Exception {
    mvc.perform(post("/v1/tweets")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("following someone without a token is refused")
  void followRequiresAToken() throws Exception {
    mvc.perform(post("/v1/users/alice/followers")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a home timeline without a token is refused")
  void timelineRequiresAToken() throws Exception {
    // There is no such thing as the logged-out user's timeline, so there is nothing to answer.
    mvc.perform(get("/v1/timelines/home")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("the health probe is reachable without a token")
  void healthIsOpen() throws Exception {
    // A probe that needs a credential turns a token outage into a full restart loop.
    mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
  }
}
