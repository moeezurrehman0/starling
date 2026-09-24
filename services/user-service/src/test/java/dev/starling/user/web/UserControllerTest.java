/* SPDX-License-Identifier: MIT */
package dev.starling.user.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.RSAKey;
import dev.starling.contracts.UserItem;
import dev.starling.user.config.SecurityConfig;
import dev.starling.user.domain.TokenIssuer;
import dev.starling.user.domain.UserService;
import dev.starling.user.persistence.FollowRepository;
import dev.starling.user.persistence.HandleAlreadyTakenException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract of {@link UserController}.
 *
 * <p>Runs with the real {@link SecurityConfig} rather than a disabled filter chain. Which endpoints
 * are reachable without a token is the most consequential thing in this package and the easiest to
 * get wrong by accident, so these tests go through the chain instead of around it.
 */
@WebMvcTest(controllers = {UserController.class, JwksController.class})
@Import({SecurityConfig.class, ErrorHandler.class})
class UserControllerTest {

  private static final String CALLER = "0193f0a0-0000-7000-8000-000000000001";
  private static final String OTHER = "0193f0a0-0000-7000-8000-000000000002";
  private static final String GOOD_PASSWORD = "correct-horse-battery";

  @Autowired private MockMvc mvc;

  @MockitoBean private UserService users;
  @MockitoBean private TokenIssuer tokens;

  /**
   * Replaces the real decoder so the slice needs no signing key. The chain, the matchers and the
   * principal wiring are all still the production ones; only signature verification is stubbed, and
   * {@code jwt()} supplies the already-verified principal.
   */
  @MockitoBean private JwtDecoder jwtDecoder;

  @MockitoBean private RSAKey signingKey;

  private static UserItem user(String id, String handle) {
    return UserItem.builder()
        .userId(id)
        .handle(handle)
        .displayName("Ada")
        .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
        .followerCount(3)
        .celebrity(false)
        .build();
  }

  private static String registerBody(String handle, String password) {
    return """
        {"handle":"%s","displayName":"Ada","password":"%s"}"""
        .formatted(handle, password);
  }

  @Test
  @DisplayName("registration returns 201 and the location of the new profile")
  void registers() throws Exception {
    when(users.register("ada", "Ada", GOOD_PASSWORD)).thenReturn(user(CALLER, "ada"));

    mvc.perform(
            post("/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("ada", GOOD_PASSWORD)))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/v1/users/" + CALLER))
        .andExpect(jsonPath("$.handle").value("ada"))
        .andExpect(jsonPath("$.followerCount").value(3));
  }

  @Test
  @DisplayName("registration never echoes the password back")
  void doesNotEchoPassword() throws Exception {
    when(users.register(any(), any(), any())).thenReturn(user(CALLER, "ada"));

    String body =
        mvc.perform(
                post("/v1/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerBody("ada", GOOD_PASSWORD)))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(GOOD_PASSWORD);
  }

  @Test
  @DisplayName("a taken handle is 409, not 500")
  void conflictOnTakenHandle() throws Exception {
    when(users.register(any(), any(), any()))
        .thenThrow(
            new HandleAlreadyTakenException(
                "ada", new RuntimeException("conditional check failed")));

    mvc.perform(
            post("/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("ada", GOOD_PASSWORD)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Handle already taken"));
  }

  @Test
  @DisplayName("a handle with a slash is rejected before it reaches the domain")
  void rejectsPathInjectingHandle() throws Exception {
    mvc.perform(
            post("/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("a/b", GOOD_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.handle").exists());

    verify(users, never()).register(any(), any(), any());
  }

  @Test
  @DisplayName("a short password is rejected")
  void rejectsShortPassword() throws Exception {
    mvc.perform(
            post("/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("ada", "short")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.password").exists());
  }

  @Test
  @DisplayName("login returns a bearer token")
  void logsIn() throws Exception {
    when(users.authenticate("ada", GOOD_PASSWORD)).thenReturn(Optional.of(user(CALLER, "ada")));
    when(tokens.issue(any())).thenReturn(new TokenIssuer.Token("signed.jwt.value", 900));

    mvc.perform(
            post("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"handle":"ada","password":"%s"}"""
                        .formatted(GOOD_PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("signed.jwt.value"))
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresIn").value(900));
  }

  @Test
  @DisplayName("a bad password and an unknown handle are indistinguishable")
  void loginFailuresAreIdentical() throws Exception {
    when(users.authenticate(any(), any())).thenReturn(Optional.empty());

    String wrongPassword =
        mvc.perform(
                post("/v1/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"handle":"ada","password":"wrong-password-here"}"""))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String unknownHandle =
        mvc.perform(
                post("/v1/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"handle":"nobody","password":"wrong-password-here"}"""))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(wrongPassword).isEqualTo(unknownHandle);
  }

  @Test
  @DisplayName("profiles are readable without a token")
  void profileIsPublic() throws Exception {
    when(users.byId(OTHER)).thenReturn(Optional.of(user(OTHER, "grace")));

    mvc.perform(get("/v1/users/" + OTHER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.handle").value("grace"));
  }

  @Test
  @DisplayName("an unknown profile is 404")
  void unknownProfile() throws Exception {
    when(users.byId(OTHER)).thenReturn(Optional.empty());

    mvc.perform(get("/v1/users/" + OTHER)).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("a profile can be fetched by handle")
  void profileByHandle() throws Exception {
    when(users.byHandle("grace")).thenReturn(Optional.of(user(OTHER, "grace")));

    mvc.perform(get("/v1/users/by-handle/grace"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(OTHER));
  }

  @Test
  @DisplayName("the public key is served to anyone, and the private half never is")
  void publishesOnlyThePublicKey() throws Exception {
    RSAKey generated =
        new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("test-key").generate();
    when(signingKey.toPublicJWK()).thenReturn(generated.toPublicJWK());

    String body =
        mvc.perform(get("/v1/jwks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.keys[0].kid").value("test-key"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // "d" is the RSA private exponent. Its presence would mean the service had published
    // the ability to mint tokens rather than the ability to check them.
    assertThat(body).doesNotContain("\"d\"");
  }

  @Test
  @DisplayName("following requires a token")
  void followRequiresAuth() throws Exception {
    mvc.perform(post("/v1/users/" + OTHER + "/followers")).andExpect(status().isUnauthorized());

    verify(users, never()).follow(any(), any());
  }

  @Test
  @DisplayName("the follower is taken from the token, never from the request")
  void followerComesFromToken() throws Exception {
    mvc.perform(
            post("/v1/users/" + OTHER + "/followers")
                .with(jwt().jwt(j -> j.subject(CALLER).claim("handle", "ada"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(true));

    verify(users).follow(CALLER, OTHER);
  }

  @Test
  @DisplayName("unfollow reports the resulting state")
  void unfollows() throws Exception {
    mvc.perform(delete("/v1/users/" + OTHER + "/followers").with(jwt().jwt(j -> j.subject(CALLER))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(false));

    verify(users).unfollow(CALLER, OTHER);
  }

  @Test
  @DisplayName("me returns the profile named by the token subject")
  void returnsOwnProfile() throws Exception {
    when(users.byId(CALLER)).thenReturn(Optional.of(user(CALLER, "ada")));

    mvc.perform(get("/v1/users/me").with(jwt().jwt(j -> j.subject(CALLER))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(CALLER));
  }

  @Test
  @DisplayName("me is not swallowed by the public profile wildcard")
  void meRequiresAToken() throws Exception {
    mvc.perform(get("/v1/users/me")).andExpect(status().isUnauthorized());

    verify(users, never()).byId(any());
  }

  @Test
  @DisplayName("a token for a deleted account is 401, not 404")
  void deletedSubjectIsUnauthenticated() throws Exception {
    when(users.byId(CALLER)).thenReturn(Optional.empty());

    mvc.perform(get("/v1/users/me").with(jwt().jwt(j -> j.subject(CALLER))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("follower pages carry a cursor")
  void pagesFollowers() throws Exception {
    when(users.followers(eq(OTHER), eq(Optional.empty()), anyInt()))
        .thenReturn(new FollowRepository.Page(List.of("a", "b"), Optional.of("cursor-1")));

    mvc.perform(get("/v1/users/" + OTHER + "/followers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.nextCursor").value("cursor-1"));
  }

  @Test
  @DisplayName("the last page has no cursor")
  void lastPageHasNoCursor() throws Exception {
    when(users.following(eq(CALLER), eq(Optional.of("cursor-1")), anyInt()))
        .thenReturn(new FollowRepository.Page(List.of("c"), Optional.empty()));

    mvc.perform(get("/v1/users/" + CALLER + "/following").param("after", "cursor-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }

  @Test
  @DisplayName("an absurd page size is clamped rather than refused")
  void clampsPageSize() throws Exception {
    when(users.followers(any(), any(), anyInt()))
        .thenReturn(new FollowRepository.Page(List.of(), Optional.empty()));

    mvc.perform(get("/v1/users/" + OTHER + "/followers").param("limit", "1000000"))
        .andExpect(status().isOk());

    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    verify(users).followers(eq(OTHER), any(), limit.capture());
    assertThat(limit.getValue()).isEqualTo(100);
  }

  @Test
  @DisplayName("a zero page size is clamped up, not passed through")
  void clampsZero() throws Exception {
    when(users.followers(any(), any(), anyInt()))
        .thenReturn(new FollowRepository.Page(List.of(), Optional.empty()));

    mvc.perform(get("/v1/users/" + OTHER + "/followers").param("limit", "0"))
        .andExpect(status().isOk());

    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    verify(users).followers(eq(OTHER), any(), limit.capture());
    assertThat(limit.getValue()).isEqualTo(1);
  }

  @Test
  @DisplayName("asking whether the caller follows an account requires a token")
  void isFollowingRequiresAuth() throws Exception {
    mvc.perform(get("/v1/users/" + OTHER + "/followers/me")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("the follow-state check uses the token subject")
  void reportsFollowState() throws Exception {
    when(users.isFollowing(CALLER, OTHER)).thenReturn(true);

    mvc.perform(get("/v1/users/" + OTHER + "/followers/me").with(jwt().jwt(j -> j.subject(CALLER))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.following").value(true));
  }

  @Test
  @DisplayName("the celebrity-followees endpoint is unpaged and public")
  void celebrityFollowing() throws Exception {
    when(users.celebrityFollowees(OTHER)).thenReturn(List.of("celeb-1", "celeb-2"));

    mvc.perform(get("/v1/users/" + OTHER + "/following/celebrities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0]").value("celeb-1"))
        // No cursor: the answer is a handful of ids even for a user following thousands, and
        // a cursor would make timeline-service page just to learn there is nothing more.
        .andExpect(jsonPath("$.nextCursor").doesNotExist());
  }
}
