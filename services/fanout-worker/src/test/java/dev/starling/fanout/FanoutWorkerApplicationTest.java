/* SPDX-License-Identifier: MIT */
package dev.starling.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.starling.platform.aws.streams.StreamHealthIndicator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Loads the real application context, which nothing else in this repository did.
 *
 * <p>Two things here are only checkable in a context.
 *
 * <p>The first is the readiness group. {@code application.yaml} names {@code stream} as a member of
 * it, and that name is a string that has to match a bean name; Boot refuses to start when it
 * matches nothing. A rename, a typo, or a condition that quietly drops the bean is therefore a
 * start-up failure in production and invisible in every unit test. Simply reaching an assertion in
 * this class proves the group resolves.
 *
 * <p>The second is that the indicator reports what it should when AWS is unreachable — which is not
 * a contrived case. It is exactly the state the worker was in when it ran for a day at {@code 1/1
 * Running} having consumed nothing: the endpoint was blocked, discovery failed once, and the pod
 * declared itself ready anyway.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      // Nothing is reachable at this endpoint, and that is the point of the test.
      "starling.dynamodb.endpoint=http://localhost:1",
      // The consumer thread stays down. This is about wiring; a live poll loop against a
      // dead endpoint would add seconds and flakiness and no coverage.
      "starling.fanout.enabled=false"
    })
@DisplayName("fanout-worker application context")
class FanoutWorkerApplicationTest {

  @Autowired private ApplicationContext context;
  @Autowired private StreamHealthIndicator stream;

  @Test
  @DisplayName("starts, which means the readiness group's `stream` member resolves")
  void contextStarts() {
    assertThat(context.containsBean("stream")).isTrue();
  }

  @Test
  @DisplayName("reports UP with fan-out off, even though the stream was never found")
  void upWhenNotConsuming() {
    assertThat(stream.health().getStatus()).isEqualTo(Status.UP);
    assertThat(stream.health().getDetails()).containsEntry("consuming", false);
  }

  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.NONE,
      properties = {
        "starling.dynamodb.endpoint=http://localhost:1",
        "starling.fanout.enabled=true"
      })
  @DisplayName("with fan-out on and nothing reachable")
  class Consuming {

    // The runner is replaced rather than left to start. Its thread is non-daemon and lives
    // for as long as the cached test context does, which is the rest of the JVM -- and a
    // stray thread called "fanout-consumer" makes FanoutRunnerTest's "starting twice does
    // not start two loops" count two. A test that breaks a different test is worse than no
    // test; this one only needs the beans, not the loop.
    @MockitoBean private dev.starling.fanout.domain.FanoutRunner runner;

    @Autowired private StreamHealthIndicator consuming;

    @Test
    @DisplayName("is out of service rather than ready and idle")
    void notReady() {
      // Force the one discovery attempt the indicator deliberately does not make itself.
      assertThat(consuming.health().getDetails()).containsEntry("consuming", true);
      assertThat(consuming.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }
  }
}
