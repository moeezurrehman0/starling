/* SPDX-License-Identifier: MIT */
package dev.starling.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GatewayApplicationTest {

  @Test
  void applicationClassIsAnnotated() {
    assertThat(GatewayApplication.class.getAnnotations()).isNotEmpty();
  }
}
