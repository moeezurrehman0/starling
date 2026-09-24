/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("RequestIdFilter")
class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  @DisplayName("stamps a request that arrives without an id")
  void generates() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {});

    assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
  }

  @Test
  @DisplayName("honours an id the caller already had")
  void propagates() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, "abc-123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    // Replacing it would sever the only link between the caller's half of the request and
    // this one, which is the entire reason the header exists.
    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123");
  }

  @Test
  @DisplayName("replaces a blank id rather than propagating emptiness")
  void blankIsReplaced() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, "   ");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank().isNotEqualTo("   ");
  }

  @Test
  @DisplayName("the id is in the MDC while the request runs")
  void inMdcDuringRequest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER, "mdc-1");
    FilterChain chain =
        (req, res) -> assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("mdc-1");

    filter.doFilter(request, new MockHttpServletResponse(), chain);
  }

  @Test
  @DisplayName("the id is cleared afterwards, even when the request failed")
  void mdcIsCleared() {
    FilterChain failing =
        (req, res) -> {
          throw new IllegalStateException("downstream blew up");
        };

    // Threads are reused. An id left behind attributes the next request's log lines to this
    // one, which is worse than having no id at all: it is a confident wrong answer.
    try {
      filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing);
    } catch (Exception expected) {
      // the filter must still clean up
    }

    assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("generates an id that is actually unique")
  void idsAreUnique() throws Exception {
    MockHttpServletResponse first = new MockHttpServletResponse();
    MockHttpServletResponse second = new MockHttpServletResponse();

    filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> {});
    filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> {});

    assertThat(first.getHeader(RequestIdFilter.HEADER))
        .isNotEqualTo(second.getHeader(RequestIdFilter.HEADER));
    assertThat(UUID.fromString(first.getHeader(RequestIdFilter.HEADER))).isNotNull();
  }
}
