/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may pass, and what a missing token means.
 *
 * <p>The gateway validates the JWT but does not trust itself to be the only place it is validated.
 * Every service behind it is an independent resource server checking the same JWKS, so a request
 * that reaches a pod by any route other than this one — a port-forward, a misconfigured Service, a
 * compromised sidecar — is still rejected. Authentication at the edge is a convenience and a place
 * to rate-limit by subject; it is not the boundary.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    // The ERROR dispatch above is permitted, and that is a correctness fix
                    // rather than a convenience. When a handler throws, the container
                    // re-dispatches to /error, Spring Security evaluates that dispatch too,
                    // and anyRequest().authenticated() rejects it -- so an unhandled 500
                    // inside a *public* endpoint reaches the caller as 401. The real fault
                    // becomes invisible and the reported one is a lie; it cost an afternoon
                    // once. Matching on the dispatch type rather than permitting the "/error"
                    // path means a client still cannot request /error directly.
                    .requestMatchers(
                        "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                    .permitAll()
                    // Sign-up, log-in and the JWKS itself cannot require a token; the first two
                    // are how a token is obtained and the third is how it is verified.
                    .requestMatchers(HttpMethod.POST, "/v1/users", "/v1/sessions")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/v1/jwks")
                    .permitAll()
                    // Reading a public profile, a tweet or a search result without an account is
                    // the product working as intended, not a hole. Writes always need a principal.
                    .requestMatchers(
                        HttpMethod.GET, "/v1/users/**", "/v1/tweets/**", "/v1/search/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    return http.build();
  }
}
