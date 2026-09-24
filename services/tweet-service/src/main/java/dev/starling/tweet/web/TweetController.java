/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.web;

import dev.starling.contracts.TweetItem;
import dev.starling.tweet.domain.MediaService;
import dev.starling.tweet.domain.TweetService;
import dev.starling.tweet.persistence.IdempotencyRepository;
import dev.starling.tweet.persistence.TweetRepository;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** HTTP surface for tweets, likes and media upload grants. */
@RestController
@RequestMapping("/v1")
public class TweetController {

  /** Largest page a client may ask for, whatever it requests. */
  private static final int MAX_PAGE = 50;

  /** Most tweets one batch read may fetch, matching DynamoDB's BatchGetItem ceiling. */
  private static final int MAX_BATCH = 100;

  private final TweetService tweets;
  private final MediaService media;
  private final IdempotencyRepository idempotency;
  private final ObjectMapper json;

  public TweetController(
      TweetService tweets,
      MediaService media,
      IdempotencyRepository idempotency,
      ObjectMapper json) {
    this.tweets = tweets;
    this.media = media;
    this.idempotency = idempotency;
    this.json = json;
  }

  /**
   * Posts a tweet.
   *
   * @param request the tweet to post
   * @param idempotencyKey optional client-supplied retry key
   * @param principal the authenticated caller
   * @return 201 with the created tweet, or 200 when replaying a completed key
   */
  @PostMapping("/tweets")
  public ResponseEntity<Api.Tweet> post(
      @Valid @RequestBody Api.PostTweet request,
      @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
      @AuthenticationPrincipal Jwt principal) {

    String userId = principal.getSubject();
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      // No key means the client has accepted the risk of a duplicate. Inventing one from the
      // body would be worse than not having it: two genuinely identical tweets posted twice
      // on purpose would silently become one.
      return created(
          tweets.post(
              userId, request.text(), request.mediaKeys(), replyTo(request), retweetOf(request)));
    }

    var claim = idempotency.claim(userId, idempotencyKey, canonical(request));
    switch (claim.outcome()) {
      case MISMATCHED -> throw new IdempotencyConflictException(idempotencyKey);
      case REPLAYED -> {
        return replay(claim.previous().orElseThrow());
      }
      default -> {
        // fall through
      }
    }

    TweetItem posted;
    try {
      posted =
          tweets.post(
              userId, request.text(), request.mediaKeys(), replyTo(request), retweetOf(request));
    } catch (RuntimeException e) {
      // The tweet was not written, so the key must not stay claimed -- otherwise a client
      // correcting and retrying a rejected request would be told its key was already used.
      idempotency.release(userId, idempotencyKey);
      throw e;
    }
    idempotency.complete(userId, idempotencyKey, HttpStatus.CREATED.value(), posted.tweetId());
    return created(posted);
  }

  private ResponseEntity<Api.Tweet> created(TweetItem posted) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .header("Location", "/v1/tweets/" + posted.tweetId())
        .body(Api.Tweet.of(posted, false));
  }

  private ResponseEntity<Api.Tweet> replay(dev.starling.contracts.IdempotencyItem previous) {
    if (!previous.isComplete()) {
      // The original request is still running, or died mid-flight. Answering with the
      // half-built resource is not possible and guessing is not acceptable, so the client is
      // told to come back -- which is precisely what 409 plus Retry-After means.
      return ResponseEntity.status(HttpStatus.CONFLICT).header("Retry-After", "1").build();
    }
    return tweets
        .byId(Optional.ofNullable(previous.resultId()).orElse(""))
        // 200, not 201. The resource was created by the earlier call, not by this one, and a
        // client that treats 201 as "I created it" would double-count.
        .map(item -> ResponseEntity.ok(Api.Tweet.of(item, null)))
        // Created, then deleted. The key is still valid, so the honest answer is that the
        // resource is gone rather than that the request never happened.
        .orElseGet(() -> ResponseEntity.status(HttpStatus.GONE).build());
  }

  /**
   * Fetches one tweet.
   *
   * @param id the tweet id
   * @param principal the caller, if any
   * @return the tweet
   */
  @GetMapping("/tweets/{id}")
  public Api.Tweet byId(@PathVariable String id, @AuthenticationPrincipal @Nullable Jwt principal) {
    TweetItem item = tweets.byId(id).orElseThrow(() -> new NotFoundException("tweet", id));
    return Api.Tweet.of(item, likedByMe(id, principal));
  }

  /**
   * Fetches several tweets at once, for rendering a timeline of ids.
   *
   * @param ids the tweet ids
   * @return those that still exist, keyed by id
   */
  @GetMapping("/tweets")
  public Api.TweetBatch byIds(
      @RequestParam List<String> ids, @AuthenticationPrincipal @Nullable Jwt principal) {
    if (ids.size() > MAX_BATCH) {
      throw new IllegalArgumentException("At most " + MAX_BATCH + " ids per request");
    }
    Map<String, Api.Tweet> out = new LinkedHashMap<>();
    // Iterating the requested ids rather than the returned map preserves the caller's order,
    // which is the timeline order. BatchGetItem makes no ordering guarantee at all.
    Map<String, TweetItem> found = tweets.byIds(ids);
    Set<String> liked = likedAmong(ids, principal);
    ids.forEach(
        id -> {
          TweetItem item = found.get(id);
          if (item != null) {
            out.put(id, Api.Tweet.of(item, likeState(id, liked, principal)));
          }
        });
    return new Api.TweetBatch(out);
  }

  /**
   * One page of an author's tweets, newest first.
   *
   * <p>Lives under {@code /v1/tweets} rather than {@code /v1/users/{id}/tweets} so the gateway can
   * route it: the gateway matches on a static prefix, and a user-scoped path would be sent to
   * user-service, which does not serve tweets.
   *
   * @param id the author
   * @param cursor cursor from the previous page
   * @param limit page size, clamped
   * @return the page
   */
  @GetMapping("/tweets/by-author/{id}")
  public Api.TweetPage byAuthor(
      @PathVariable String id,
      @RequestParam(required = false) @Nullable String cursor,
      @RequestParam(defaultValue = "20") int limit,
      @AuthenticationPrincipal @Nullable Jwt principal) {

    TweetRepository.TweetPage page =
        tweets.byAuthor(id, Optional.ofNullable(cursor), Math.clamp(limit, 1, MAX_PAGE));
    List<String> ids = page.tweets().stream().map(TweetItem::tweetId).toList();
    Set<String> liked = likedAmong(ids, principal);
    return new Api.TweetPage(
        page.tweets().stream()
            .map(item -> Api.Tweet.of(item, likeState(item.tweetId(), liked, principal)))
            .toList(),
        page.next().orElse(null));
  }

  /**
   * Deletes a tweet.
   *
   * @param id the tweet
   * @param principal the caller, who must be the author
   * @return 204 whether or not the tweet existed
   */
  @DeleteMapping("/tweets/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable String id, @AuthenticationPrincipal Jwt principal) {
    // 204 for an already-absent tweet as well as a just-deleted one. Distinguishing them
    // would tell an unauthenticated-enough caller which ids exist, and would make DELETE
    // non-idempotent for no benefit.
    tweets.delete(id, principal.getSubject());
    return ResponseEntity.noContent().build();
  }

  /**
   * Likes a tweet.
   *
   * @param id the tweet
   * @param principal the caller
   * @return the like state after the call
   */
  @PostMapping("/tweets/{id}/likes")
  public Api.LikeState like(@PathVariable String id, @AuthenticationPrincipal Jwt principal) {
    return new Api.LikeState(tweets.like(id, principal.getSubject()), true);
  }

  /**
   * Removes a like.
   *
   * @param id the tweet
   * @param principal the caller
   * @return the like state after the call
   */
  @DeleteMapping("/tweets/{id}/likes")
  public Api.LikeState unlike(@PathVariable String id, @AuthenticationPrincipal Jwt principal) {
    return new Api.LikeState(tweets.unlike(id, principal.getSubject()), false);
  }

  /**
   * Issues a direct-to-S3 upload grant.
   *
   * @param request the type and size the client intends to upload
   * @param principal the caller, whose id becomes the key prefix
   * @return the grant
   */
  @PostMapping("/media/uploads")
  public Api.UploadGrant upload(
      @Valid @RequestBody Api.UploadRequest request, @AuthenticationPrincipal Jwt principal) {
    var grant = media.grant(principal.getSubject(), request.contentType(), request.contentLength());
    return new Api.UploadGrant(grant.key(), grant.uploadUrl(), grant.expiresAt());
  }

  private @Nullable Boolean likedByMe(String tweetId, @Nullable Jwt principal) {
    // null, not false, for an anonymous read. False would assert the caller has not liked it,
    // which is not something an anonymous request can be told.
    return principal == null ? null : tweets.hasLiked(tweetId, principal.getSubject());
  }

  /**
   * The caller's likes among a page of tweets, in one round trip.
   *
   * <p>Empty for an anonymous caller, who has no likes to report; {@link #likeState} turns that
   * into {@code null} rather than {@code false} so the two stay distinguishable.
   */
  private Set<String> likedAmong(List<String> tweetIds, @Nullable Jwt principal) {
    return principal == null ? Set.of() : tweets.likedAmong(tweetIds, principal.getSubject());
  }

  private static @Nullable Boolean likeState(
      String tweetId, Set<String> liked, @Nullable Jwt principal) {
    return principal == null ? null : liked.contains(tweetId);
  }

  private static Optional<String> replyTo(Api.PostTweet request) {
    return Optional.ofNullable(request.replyTo()).filter(s -> !s.isBlank());
  }

  private static Optional<String> retweetOf(Api.PostTweet request) {
    return Optional.ofNullable(request.retweetOf()).filter(s -> !s.isBlank());
  }

  /**
   * Serialises the request for hashing.
   *
   * <p>The parsed record is re-serialised rather than the raw body being hashed, so that
   * whitespace, key order and absent-versus-null differences between a request and its retry do not
   * read as two different requests.
   */
  private String canonical(Api.PostTweet request) {
    return json.writeValueAsString(request);
  }
}
