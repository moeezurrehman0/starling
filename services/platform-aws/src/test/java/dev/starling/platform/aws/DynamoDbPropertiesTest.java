/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DynamoDbPropertiesTest {

  @Test
  @DisplayName("an unset prefix leaves logical names alone")
  void noPrefix() {
    assertThat(new DynamoDbProperties(null, null).table("tweets")).isEqualTo("tweets");
  }

  @Test
  @DisplayName("a prefix is prepended verbatim, separator included")
  void prefixed() {
    // Verbatim, with no separator inserted. A helpful "-" here would mean the deployed name
    // depends on whether the operator remembered the convention, and Terraform builds the
    // real names from the same string.
    assertThat(new DynamoDbProperties(null, "sandbox-").table("tweets"))
        .isEqualTo("sandbox-tweets");
  }

  @Test
  @DisplayName("a null endpoint means the SDK resolves the real regional one")
  void endpointIsOptional() {
    assertThat(new DynamoDbProperties(null, "").endpoint()).isNull();
    assertThat(new DynamoDbProperties(URI.create("http://localhost:4566"), "").endpoint())
        .hasToString("http://localhost:4566");
  }
}
