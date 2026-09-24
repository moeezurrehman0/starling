/* SPDX-License-Identifier: MIT */
package dev.starling.user.web;

import dev.starling.contracts.UserItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Request and response bodies for the {@code /v1} API.
 *
 * <p>Separate from the item records on purpose. {@link UserItem} is a description of a stored row
 * and changing it is a data migration; these are a description of a wire format and changing them
 * breaks clients. Returning the item record directly would fuse the two, so that abbreviating a
 * column to save storage would silently rename a JSON field.
 */
public final class Api {

  private Api() {}

  /**
   * A new account.
   *
   * @param handle the requested handle, letters digits and underscore only
   * @param displayName the name shown on the profile
   * @param password the raw password, never stored or logged
   */
  public record RegisterRequest(
      @NotBlank
          @Size(min = 3, max = 15)
          // Anchored, and deliberately narrower than "not blank". A handle appears in a URL
          // path and is the key of two tables; allowing dots or slashes would make
          // /v1/users/a/b ambiguous and allowing unicode would make two visually identical
          // handles distinct keys.
          @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "letters, digits and underscore only")
          String handle,
      @NotBlank @Size(max = 50) String displayName,
      // Long rather than complex. Composition rules push users towards predictable
      // substitutions; length is the property that actually costs an attacker.
      @NotBlank @Size(min = 12, max = 128) String password) {}

  /** Credentials presented at login. */
  public record LoginRequest(@NotBlank String handle, @NotBlank String password) {}

  /**
   * An issued access token.
   *
   * @param accessToken a signed JWT the gateway validates on every subsequent request
   * @param expiresIn seconds until it stops being accepted
   */
  public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

  /**
   * A public profile.
   *
   * <p>Carries {@code celebrity} because the client uses it, not merely because it is stored: a
   * celebrity's tweets reach a timeline by a different path and can lag a moment behind, and the
   * interface says so rather than looking broken.
   */
  public record Profile(
      String id,
      String handle,
      String displayName,
      @Nullable String bio,
      @Nullable String avatarUrl,
      long followerCount,
      boolean celebrity,
      Instant createdAt) {

    public static Profile of(UserItem item) {
      return new Profile(
          item.userId(),
          item.handle(),
          item.displayName(),
          item.bio(),
          item.avatarUrl(),
          item.followerCount(),
          item.celebrity(),
          item.createdAt());
    }
  }

  /**
   * One page of user ids.
   *
   * @param items the ids on this page
   * @param nextCursor pass back as {@code after} to continue, or null at the end
   */
  public record UserPage(List<String> items, @Nullable String nextCursor) {}

  /** Whether the caller follows a given account. */
  public record FollowState(boolean following) {}
}
