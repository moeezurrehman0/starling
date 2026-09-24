/* SPDX-License-Identifier: MIT */
package dev.starling.user.web;

import com.nimbusds.jose.jwk.RSAKey;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public half of the signing key.
 *
 * <p>So the gateway and every other service can verify a token without being configured with a copy
 * of the key, and so rotating it is a restart of this service rather than a coordinated redeploy of
 * all of them. Only the public half is ever serialised here -- {@code toPublicJWK()} is what makes
 * that a property of the code rather than of the configuration.
 */
@RestController
public class JwksController {

  private final RSAKey signingKey;

  public JwksController(RSAKey signingKey) {
    this.signingKey = signingKey;
  }

  /**
   * The JSON Web Key Set.
   *
   * @return a single-key set containing the public half of the current signing key
   */
  @GetMapping("/v1/jwks")
  public Map<String, Object> jwks() {
    return Map.of("keys", java.util.List.of(signingKey.toPublicJWK().toJSONObject()));
  }
}
