/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserServiceApplicationTest {

  @Test
  void applicationClassIsAnnotated() {
    assertThat(UserServiceApplication.class.getAnnotations()).isNotEmpty();
  }
}
