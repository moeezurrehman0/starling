/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.starling.tweet.config.MediaProperties;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * What an upload grant will and will not be issued for.
 *
 * <p>The presigner itself is mocked: signing is the SDK's job and testing it here would test
 * Amazon's code. What is tested is the decision to sign at all, which is this project's code and is
 * the part that keeps somebody else's bytes out of somebody else's prefix.
 */
class MediaServiceTest {

  private static final String USER = "0193f0a0-0000-7000-8000-000000000001";

  private S3Presigner presigner;
  private MediaService media;

  @BeforeEach
  void setUp() throws MalformedURLException {
    presigner = mock(S3Presigner.class);
    PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
    when(presigned.url()).thenReturn(URI.create("https://s3.example/put").toURL());
    when(presigner.presignPutObject(
            any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class)))
        .thenReturn(presigned);
    media =
        new MediaService(
            presigner, new MediaProperties("bucket", null, Duration.ofMinutes(10), 1024));
  }

  @Test
  @DisplayName("a grant is keyed under the uploader's prefix")
  void grantIsPrefixed() {
    MediaService.Grant grant = media.grant(USER, "image/png", 512);

    assertThat(grant.key()).startsWith("media/" + USER + "/");
    assertThat(grant.uploadUrl()).isEqualTo("https://s3.example/put");
    assertThat(grant.expiresAt()).isNotNull();
  }

  @Test
  @DisplayName("two grants never collide")
  void grantsAreUnique() {
    assertThat(media.grant(USER, "image/png", 1).key())
        .isNotEqualTo(media.grant(USER, "image/png", 1).key());
  }

  @ParameterizedTest
  @ValueSource(strings = {"text/html", "application/pdf", "image/svg+xml", "IMAGE/PNG"})
  @DisplayName("only the allow-listed image types may be uploaded")
  void unsupportedTypesAreRefused(String contentType) {
    // text/html in particular: served from a CDN on the site's own domain it is a stored XSS
    // vector, and svg carries script too. An allow-list is the only shape that stays correct
    // as new types appear.
    assertThatThrownBy(() -> media.grant(USER, contentType, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported media type");
  }

  @Test
  @DisplayName("an oversized upload is refused before it is signed")
  void oversizedIsRefused() {
    assertThatThrownBy(() -> media.grant(USER, "image/png", 1025))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 1024");
  }

  @Test
  @DisplayName("a zero-length upload is refused")
  void emptyIsRefused() {
    assertThatThrownBy(() -> media.grant(USER, "image/png", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a key this service issued is recognised as the caller's")
  void ownsItsOwnKeys() {
    assertThat(media.isOwnedBy(USER, media.grant(USER, "image/png", 10).key())).isTrue();
  }

  @Test
  @DisplayName("another user's key is not the caller's")
  void rejectsAnotherUsersKey() {
    String theirs = media.grant("someone-else", "image/png", 10).key();
    assertThat(media.isOwnedBy(USER, theirs)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "media/USER/../someone-else/object",
        "media/USER/nested/object",
        "media/USERX/object",
        "other/USER/object",
        "USER/object"
      })
  @DisplayName("keys that only look like the caller's are rejected")
  void rejectsLookalikes(String template) {
    // The prefix check alone passes the traversal case: S3 stores "../" literally, but a CDN
    // or a path-normalising proxy in front of it may not, and then the key resolves somewhere
    // it was never granted.
    assertThat(media.isOwnedBy(USER, template.replace("USER", USER))).isFalse();
  }

  @Test
  @DisplayName("nothing is signed when validation fails")
  void refusalDoesNotSign() {
    assertThatThrownBy(() -> media.grant(USER, "text/html", 10))
        .isInstanceOf(IllegalArgumentException.class);

    org.mockito.Mockito.verifyNoInteractions(presigner);
  }

  @Test
  @DisplayName("the URL the SDK produced is passed through unchanged")
  void urlIsPassedThrough() throws MalformedURLException {
    URL url = URI.create("https://s3.example/put?X-Amz-Signature=abc").toURL();
    PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
    when(presigned.url()).thenReturn(url);
    when(presigner.presignPutObject(
            any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class)))
        .thenReturn(presigned);

    assertThat(media.grant(USER, "image/jpeg", 10).uploadUrl()).isEqualTo(url.toString());
  }
}
