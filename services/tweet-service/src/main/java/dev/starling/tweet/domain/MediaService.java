/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.domain;

import dev.starling.contracts.Ids;
import dev.starling.tweet.config.MediaProperties;
import java.net.URL;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Issues time-limited, direct-to-S3 upload grants.
 *
 * <p>The alternative — accepting a multipart body and forwarding it — would put every uploaded
 * image through a pod, so the request path would need memory and timeouts sized for the largest
 * file rather than for the largest tweet, and a burst of uploads would starve text posts of
 * connections. Presigning moves the bytes to a path that scales independently and costs this
 * service one signature.
 */
@Service
public class MediaService {

  /**
   * What a grant may be issued for.
   *
   * <p>An allow-list, not a deny-list. The content type is baked into the signature, so anything
   * not named here cannot be uploaded at all — which matters because {@code text/html} in a bucket
   * served by a CDN on the site's own domain is a stored cross-site scripting vector.
   */
  private static final Set<String> ALLOWED_TYPES =
      Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

  private final S3Presigner presigner;
  private final MediaProperties properties;

  public MediaService(S3Presigner presigner, MediaProperties properties) {
    this.presigner = presigner;
    this.properties = properties;
  }

  /**
   * Creates an upload grant.
   *
   * @param userId who is uploading, which becomes the key prefix
   * @param contentType the declared type, which is signed into the grant
   * @param contentLength the declared size, also signed
   * @return the key to store on the tweet and the URL to PUT to
   * @throws IllegalArgumentException if the type is not allowed or the size is out of range
   */
  public Grant grant(String userId, String contentType, long contentLength) {
    if (!ALLOWED_TYPES.contains(contentType)) {
      throw new IllegalArgumentException("Unsupported media type: " + contentType);
    }
    if (contentLength <= 0 || contentLength > properties.maxBytes()) {
      throw new IllegalArgumentException(
          "Media must be between 1 and " + properties.maxBytes() + " bytes");
    }

    // The uploader's id is the first path segment, so a bucket policy can restrict a
    // compromised credential to one user's prefix, and so an abusive account's objects can be
    // found and removed by prefix rather than by scanning. The object id is a UUIDv7, which
    // makes the keyspace time-ordered and lifecycle rules expressible as a prefix range.
    String key = "media/" + userId + "/" + Ids.newId();

    PutObjectRequest put =
        PutObjectRequest.builder()
            .bucket(properties.bucket())
            .key(key)
            // Both of these are part of what gets signed. A client that PUTs a different
            // type or a different length produces a signature mismatch and is rejected by
            // S3 -- so the limit holds without this service ever seeing a byte.
            .contentType(contentType)
            .contentLength(contentLength)
            .build();

    URL url =
        presigner
            .presignPutObject(
                PutObjectPresignRequest.builder()
                    .signatureDuration(properties.uploadTtl())
                    .putObjectRequest(put)
                    .build())
            .url();

    return new Grant(key, url.toString(), Instant.now().plus(properties.uploadTtl()));
  }

  /**
   * Whether a media key could have been issued by this service for this user.
   *
   * <p>Checked when a tweet is posted. Without it a client could claim any key in the bucket,
   * including another user's unpublished upload, simply by naming it.
   *
   * @param userId the poster
   * @param key the key they claim
   * @return true if the key is in that user's prefix
   */
  public boolean isOwnedBy(String userId, String key) {
    return key.startsWith("media/" + userId + "/")
        // Rejects traversal in the remainder. The prefix check alone would pass
        // "media/<me>/../<someone-else>/x", which S3 stores literally but a CDN or a
        // downstream path-normalising proxy may not.
        && !key.contains("..")
        && key.indexOf('/', ("media/" + userId + "/").length()) < 0;
  }

  /**
   * A grant to upload one object.
   *
   * @param key the object key, which is what gets stored on the tweet
   * @param uploadUrl the presigned URL the client PUTs to
   * @param expiresAt when the URL stops working
   */
  public record Grant(String key, String uploadUrl, Instant expiresAt) {}
}
