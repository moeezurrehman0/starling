/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TweetServiceApplicationTest {

  @Test
  void applicationClassIsAnnotated() {
    assertThat(TweetServiceApplication.class.getAnnotations()).isNotEmpty();
  }
}
