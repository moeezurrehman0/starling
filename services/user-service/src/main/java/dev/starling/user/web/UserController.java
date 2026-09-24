/* SPDX-License-Identifier: MIT */
package dev.starling.user.web;

import dev.starling.contracts.UserItem;
import dev.starling.user.domain.TokenIssuer;
import dev.starling.user.domain.UserService;
import dev.starling.user.persistence.FollowRepository;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Accounts, profiles and the follow graph. */
@RestController
@RequestMapping("/v1")
public class UserController {

  /**
   * The largest page this API will return.
   *
   * <p>Capped rather than trusted, because the follower list of a celebrity is millions of rows and
   * {@code ?limit=1000000} is a denial of service that looks like a query string.
   */
  private static final int MAX_PAGE = 100;

  private static final int DEFAULT_PAGE = 20;

  private final UserService users;
  private final TokenIssuer tokens;

  public UserController(UserService users, TokenIssuer tokens) {
    this.users = users;
    this.tokens = tokens;
  }

  /**
   * Creates an account.
   *
   * @param request the requested handle, display name and password
   * @return 201 with the new profile, or 409 if the handle is taken
   */
  @PostMapping("/users")
  public ResponseEntity<Api.Profile> register(@Valid @RequestBody Api.RegisterRequest request) {
    UserItem created = users.register(request.handle(), request.displayName(), request.password());
    return ResponseEntity.created(URI.create("/v1/users/" + created.userId()))
        .body(Api.Profile.of(created));
  }

  /**
   * Exchanges credentials for an access token.
   *
   * <p>A wrong password and an unknown handle both return 401 with the same body. Saying which one
   * it was turns the login form into a way to ask whether an account exists.
   *
   * @param request the handle and password
   * @return 200 with a token, or 401
   */
  @PostMapping("/sessions")
  public ResponseEntity<Api.TokenResponse> login(@Valid @RequestBody Api.LoginRequest request) {
    return users
        .authenticate(request.handle(), request.password())
        .map(tokens::issue)
        .map(t -> new Api.TokenResponse(t.value(), "Bearer", t.expiresInSeconds()))
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.status(401).build());
  }

  /**
   * The caller's own profile.
   *
   * @param principal the verified token
   * @return 200 with the profile
   */
  @GetMapping("/users/me")
  public Api.Profile me(@AuthenticationPrincipal Jwt principal) {
    return users
        .byId(principal.getSubject())
        .map(Api.Profile::of)
        // A valid token whose subject no longer exists means a deleted account, not a bad
        // request. 401 rather than 404: the credential is what is no longer good.
        .orElseThrow(() -> new UnauthenticatedException("Token subject no longer exists"));
  }

  /**
   * A public profile by id.
   *
   * @param userId the account id
   * @return 200 with the profile, or 404
   */
  @GetMapping("/users/{userId}")
  public Api.Profile byId(@PathVariable String userId) {
    return users.byId(userId).map(Api.Profile::of).orElseThrow(NotFoundException::new);
  }

  /**
   * A public profile by handle.
   *
   * @param handle the account handle, matched case-insensitively
   * @return 200 with the profile, or 404
   */
  @GetMapping("/users/by-handle/{handle}")
  public Api.Profile byHandle(@PathVariable String handle) {
    return users.byHandle(handle).map(Api.Profile::of).orElseThrow(NotFoundException::new);
  }

  /**
   * Follows an account.
   *
   * <p>Idempotent: following twice is a success, not a conflict. A retried request after a timeout
   * must not become an error the client has to distinguish from a real one.
   *
   * @param userId the account to follow
   * @param principal the verified token
   * @return 200 with the resulting state
   */
  @PostMapping("/users/{userId}/followers")
  public Api.FollowState follow(
      @PathVariable String userId, @AuthenticationPrincipal Jwt principal) {
    users.follow(principal.getSubject(), userId);
    return new Api.FollowState(true);
  }

  /**
   * Unfollows an account.
   *
   * @param userId the account to unfollow
   * @param principal the verified token
   * @return 200 with the resulting state
   */
  @DeleteMapping("/users/{userId}/followers")
  public Api.FollowState unfollow(
      @PathVariable String userId, @AuthenticationPrincipal Jwt principal) {
    users.unfollow(principal.getSubject(), userId);
    return new Api.FollowState(false);
  }

  /**
   * One page of the accounts following this one.
   *
   * @param userId the account
   * @param after the cursor from the previous page, absent for the first
   * @param limit page size, clamped to 100
   * @return 200 with ids and a cursor
   */
  @GetMapping("/users/{userId}/followers")
  public Api.UserPage followers(
      @PathVariable String userId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit) {
    FollowRepository.Page page = users.followers(userId, Optional.ofNullable(after), clamp(limit));
    return new Api.UserPage(page.ids(), page.next().orElse(null));
  }

  /**
   * One page of the accounts this one follows.
   *
   * @param userId the account
   * @param after the cursor from the previous page, absent for the first
   * @param limit page size, clamped to 100
   * @return 200 with ids and a cursor
   */
  @GetMapping("/users/{userId}/following")
  public Api.UserPage following(
      @PathVariable String userId,
      @RequestParam(required = false) String after,
      @RequestParam(required = false) Integer limit) {
    FollowRepository.Page page = users.following(userId, Optional.ofNullable(after), clamp(limit));
    return new Api.UserPage(page.ids(), page.next().orElse(null));
  }

  /**
   * The celebrity accounts this one follows.
   *
   * <p>Consumed by timeline-service, which needs to know whose tweets it must pull at read time
   * because they were deliberately never fanned out. Unpaged on purpose: the answer is a handful of
   * ids even for a user following thousands of accounts, and a cursor here would make the caller
   * page just to discover there was nothing more.
   *
   * @param userId the account
   * @return 200 with the celebrity ids
   */
  @GetMapping("/users/{userId}/following/celebrities")
  public Api.UserPage celebrityFollowing(@PathVariable String userId) {
    return new Api.UserPage(users.celebrityFollowees(userId), null);
  }

  /**
   * Whether the caller follows an account.
   *
   * @param userId the account
   * @param principal the verified token
   * @return 200 with the state
   */
  @GetMapping("/users/{userId}/followers/me")
  public Api.FollowState isFollowing(
      @PathVariable String userId, @AuthenticationPrincipal Jwt principal) {
    return new Api.FollowState(users.isFollowing(principal.getSubject(), userId));
  }

  private static int clamp(Integer requested) {
    if (requested == null) {
      return DEFAULT_PAGE;
    }
    return Math.clamp(requested, 1, MAX_PAGE);
  }
}
