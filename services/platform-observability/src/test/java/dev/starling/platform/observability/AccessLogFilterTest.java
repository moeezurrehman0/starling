/* SPDX-License-Identifier: MIT */
package dev.starling.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessLogFilterTest {

  private final AccessLogFilter filter = new AccessLogFilter();

  @Test
  void filtersApplicationRequests() {
    assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/v1/tweets"))).isFalse();
  }

  /**
   * The exclusions are prefix matches, so this also covers {@code /actuator/health/liveness} and
   * {@code /actuator/health/readiness}, which is what the kubelet actually calls.
   */
  @Test
  void skipsProbeAndScrapeEndpoints() {
    assertThat(
            filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health/readiness")))
        .isTrue();
    assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/prometheus")))
        .isTrue();
  }

  @Test
  void passesTheRequestDownTheChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/timelines/home");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter.doFilter(request, response, chain);

    assertThat(chain.getRequest()).isSameAs(request);
    assertThat(response.getStatus()).isEqualTo(200);
  }
}
