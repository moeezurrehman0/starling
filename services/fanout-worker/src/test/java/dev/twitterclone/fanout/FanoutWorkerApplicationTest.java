/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FanoutWorkerApplicationTest {

  @Test
  void applicationClassIsAnnotated() {
    assertThat(FanoutWorkerApplication.class.getAnnotations()).isNotEmpty();
  }
}
