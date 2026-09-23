/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * This service is a pure resource server: it validates tokens, it never issues them.
 *
 * <p>The signing key is fetched from user-service's JWKS endpoint and cached, so rotating the key
 * pair there requires no deployment here. Sharing a symmetric secret instead would mean every
 * service that can verify a token can also mint one, and a single compromised pod would be able to
 * impersonate anybody.
 */
@Configuration(proxyBeanMethods = false)
// Explicit rather than inherited from a starter. It is this annotation that contributes the
// HttpSecurity prototype, so without it the bean below is unconstructable in any context --
// including a test slice -- that imports this class directly.
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        // No browser-session state, so nothing for a forged cross-site form to ride on. The
        // token travels in an Authorization header, which a cross-origin form cannot set.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth
                    // Order is the security property here, not decoration. Every rule that
                    // requires a token is stated before the read rules, because a wildcard
                    // permitAll matched first does not produce a 401 -- it produces an
                    // anonymous request, a null principal and a 500.
                    .requestMatchers(HttpMethod.POST, "/v1/tweets", "/v1/media/uploads")
                    .authenticated()
                    .requestMatchers(HttpMethod.DELETE, "/v1/tweets/**")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/v1/tweets/*/likes")
                    .authenticated()
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    // Reads are open, but the handler still inspects the principal: a request
                    // that does carry a valid token gets likedByMe populated. permitAll means
                    // "a token is not required", not "a token is ignored".
                    .requestMatchers(HttpMethod.GET, "/v1/tweets/**", "/v1/users/*/tweets")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    return http.build();
  }
}
