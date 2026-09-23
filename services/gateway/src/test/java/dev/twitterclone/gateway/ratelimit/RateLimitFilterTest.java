/* SPDX-License-Identifier: MIT */
package dev.twitterclone.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

  @Mock private TokenBucket bucket;

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private RateLimitFilter filter() {
    when(bucket.period()).thenReturn(Duration.ofSeconds(1));
    return new RateLimitFilter(bucket);
  }

  private void allow() {
    when(bucket.spend(anyString())).thenReturn(new TokenBucket.Decision(true, 9));
  }

  private void refuse() {
    when(bucket.spend(anyString())).thenReturn(new TokenBucket.Decision(false, 0));
  }

  private static void authenticateAs(String subject) {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(subject)
            .claim("sub", subject)
            .build();
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(jwt, "n/a", List.of()));
  }

  @Test
  @DisplayName("lets an allowed request through")
  void allowed() throws Exception {
    allow();
    boolean[] reached = {false};

    filter()
        .doFilter(
            new MockHttpServletRequest(),
            new MockHttpServletResponse(),
            (req, res) -> reached[0] = true);

    assertThat(reached[0]).isTrue();
  }

  @Test
  @DisplayName("answers 429 without calling the upstream")
  void refused() throws Exception {
    refuse();
    MockHttpServletResponse response = new MockHttpServletResponse();
    boolean[] reached = {false};

    filter().doFilter(new MockHttpServletRequest(), response, (req, res) -> reached[0] = true);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(reached[0]).isFalse();
  }

  @Test
  @DisplayName("tells a refused caller when to come back")
  void retryAfter() throws Exception {
    refuse();
    MockHttpServletResponse response = new MockHttpServletResponse();

    // Without Retry-After a well-behaved client has no choice but to guess, and the usual
    // guess is "immediately", which turns throttling into a tight loop.
    filter().doFilter(new MockHttpServletRequest(), response, (req, res) -> {});

    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
  }

  @Test
  @DisplayName("reports the remaining budget on every response")
  void remainingHeader() throws Exception {
    allow();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter().doFilter(new MockHttpServletRequest(), response, (req, res) -> {});

    assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("9");
  }

  @Test
  @DisplayName("buckets an authenticated caller by subject")
  void keyedBySubject() throws Exception {
    allow();
    authenticateAs("user-7");

    filter()
        .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {});

    verify(bucket).spend("u:user-7");
  }

  @Test
  @DisplayName("buckets an anonymous caller by address")
  void keyedByAddress() throws Exception {
    allow();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.7");

    // Sign-up and log-in have no subject and are the two endpoints worth attacking. Keying
    // only by subject would leave exactly those unlimited.
    filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

    verify(bucket).spend("ip:198.51.100.7");
  }

  @Test
  @DisplayName("takes the original client from X-Forwarded-For, not the load balancer")
  void forwardedFor() throws Exception {
    allow();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1, 10.0.0.2");
    request.setRemoteAddr("10.0.0.2");

    // Every request arrives from the load balancer's address. Trusting the right-most entry
    // would put the entire internet in one bucket.
    filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

    ArgumentCaptor<String> key = ArgumentCaptor.captor();
    verify(bucket).spend(key.capture());
    assertThat(key.getValue()).isEqualTo("ip:203.0.113.9");
  }

  @Test
  @DisplayName("falls back to the socket address when the forwarded header is blank")
  void blankForwardedFor() throws Exception {
    allow();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Forwarded-For", "  ");
    request.setRemoteAddr("198.51.100.8");

    filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

    verify(bucket).spend("ip:198.51.100.8");
  }

  @Test
  @DisplayName("never throttles the probes")
  void actuatorIsExempt() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/actuator/health/readiness");

    // A throttled readiness probe reads as a dead pod, so the rate limiter would restart the
    // service it was protecting.
    assertThat(new RateLimitFilter(bucket).shouldNotFilter(request)).isTrue();
    verify(bucket, never()).spend(anyString());
  }

  @Test
  @DisplayName("an unauthenticated context is bucketed by address, not rejected")
  void unauthenticatedContext() throws Exception {
    allow();
    SecurityContextHolder.clearContext();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.9");

    filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

    verify(bucket).spend("ip:198.51.100.9");
  }

  @Test
  @DisplayName("a non-JWT principal is bucketed by address")
  void nonJwtPrincipal() throws Exception {
    allow();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("plain-string", "n/a", List.of()));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.10");

    filter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

    verify(bucket).spend("ip:198.51.100.10");
  }

  @Test
  @DisplayName("ordinary paths are filtered")
  void ordinaryPathsAreFiltered() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI("/v1/tweets");

    assertThat(new RateLimitFilter(bucket).shouldNotFilter(request)).isFalse();
  }
}
