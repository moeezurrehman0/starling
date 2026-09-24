/* SPDX-License-Identifier: MIT */
package dev.starling.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TimelineServiceApplicationTest {

  @Test
  void applicationClassIsAnnotated() {
    assertThat(TimelineServiceApplication.class.getAnnotations()).isNotEmpty();
  }
}
