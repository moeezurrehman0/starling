/* SPDX-License-Identifier: MIT */
package dev.starling.user.domain;

import dev.starling.contracts.Ids;
import dev.starling.contracts.UserItem;
import dev.starling.user.persistence.FollowRepository;
import dev.starling.user.persistence.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Account lifecycle and the follow graph. */
@Service
public class UserService {

  /**
   * Page size used when walking a following list internally.
   *
   * <p>Matched to DynamoDB's BatchGetItem ceiling so each page of follow rows becomes exactly one
   * batch read rather than a page plus a remainder.
   */
  private static final int FOLLOWING_SCAN_PAGE = 100;

  private static final Logger LOG = LoggerFactory.getLogger(UserService.class);

  private final UserRepository users;
  private final FollowRepository follows;
  private final PasswordEncoder passwordEncoder;

  public UserService(
      UserRepository users, FollowRepository follows, PasswordEncoder passwordEncoder) {
    this.users = users;
    this.follows = follows;
    this.passwordEncoder = passwordEncoder;
  }

  /**
   * Registers an account, claiming the handle atomically.
   *
   * @throws dev.starling.user.persistence.HandleAlreadyTakenException if the handle is gone
   */
  public UserItem register(String handle, String displayName, String rawPassword) {
    UserItem user =
        UserItem.builder()
            // A UUIDv7, so the id itself sorts by creation time. Nothing in this service needs
            // that, but tweet ids and timeline keys do, and minting every id the same way is
            // what keeps the property from being something each service remembers separately.
            .userId(Ids.newId().toString())
            .handle(handle)
            .displayName(displayName)
            .followerCount(0L)
            .celebrity(false)
            .createdAt(Instant.now())
            .build();
    return users.createWithHandle(user, passwordEncoder.encode(rawPassword));
  }

  /**
   * Verifies a handle and password.
   *
   * <p>An unknown handle still costs a password hash comparison. Returning early would make the
   * request measurably faster, and that difference is enough to enumerate which handles exist.
   */
  public Optional<UserItem> authenticate(String handle, String rawPassword) {
    String storedHash = users.passwordHash(handle).orElse(DUMMY_HASH);
    if (!passwordEncoder.matches(rawPassword, storedHash)) {
      return Optional.empty();
    }
    return users.findByHandle(handle);
  }

  /**
   * Follows an account and keeps the followee's follower count and celebrity flag current.
   *
   * @return {@code true} if this created a new edge
   */
  public boolean follow(String followerId, String followeeId) {
    if (followerId.equals(followeeId)) {
      throw new IllegalArgumentException("an account cannot follow itself");
    }
    if (!follows.follow(followerId, followeeId)) {
      return false;
    }
    // The edge is written first and the count second, never the reverse. If this process dies
    // between the two, the count is low by one, which shows as a slightly stale number on a
    // profile. The other order would leave a count with no edge behind it, which is a number
    // nothing can ever reconcile back to the truth.
    applyFollowerDelta(followeeId, +1);
    return true;
  }

  /**
   * Unfollows an account.
   *
   * @return {@code true} if an edge was removed
   */
  public boolean unfollow(String followerId, String followeeId) {
    if (!follows.unfollow(followerId, followeeId)) {
      return false;
    }
    applyFollowerDelta(followeeId, -1);
    return true;
  }

  /**
   * Moves the follower count and, if the account has just crossed the threshold, the celebrity flag
   * with it.
   *
   * <p>The flag decides how this account's tweets are delivered: below the line they are fanned out
   * into every follower's timeline at write time, above it they are merged in at read time instead.
   * Getting the transition wrong in the upward direction is the expensive mistake -- fanning out to
   * millions of timelines is what the flag exists to prevent.
   */
  private void applyFollowerDelta(String userId, long delta) {
    UserItem updated = users.adjustFollowerCount(userId, delta);
    boolean shouldBeCelebrity = updated.followerCount() >= UserItem.CELEBRITY_THRESHOLD;

    if (shouldBeCelebrity == updated.celebrity()) {
      return;
    }
    // Conditional on the flag still holding its old value, so concurrent follows racing across
    // the threshold produce exactly one winner rather than one flip per request.
    if (users.setCelebrity(userId, shouldBeCelebrity)) {
      LOG.info(
          "celebrity flag {} for user {} at {} followers",
          shouldBeCelebrity ? "set" : "cleared",
          userId,
          updated.followerCount());
    }
  }

  public Optional<UserItem> byId(String userId) {
    return users.findById(userId);
  }

  public Optional<UserItem> byHandle(String handle) {
    return users.findByHandle(handle);
  }

  public FollowRepository.Page followers(String userId, Optional<String> after, int limit) {
    return follows.followers(userId, after, limit);
  }

  public FollowRepository.Page following(String userId, Optional<String> after, int limit) {
    return follows.following(userId, after, limit);
  }

  /**
   * The celebrity accounts a user follows.
   *
   * <p>This is what makes the hybrid timeline possible. A celebrity's tweets are deliberately
   * <em>not</em> fanned out — writing one post into ten million inboxes is the thing the design
   * refuses to do — so the read path has to know which of a user's followees it must pull from
   * instead of finding pre-materialised.
   *
   * <p>The cost is honest and worth stating: this walks the whole following list and batch-reads
   * every account in it. It is O(following), not O(celebrities), because the {@code follows} table
   * does not record what kind of account the followee is, and denormalising that would mean
   * rewriting every follower's row whenever an account is promoted. Callers are expected to cache
   * the answer; timeline-service does, in the celebrity cache, where one entry serves every read
   * that user makes.
   *
   * @param userId whose following list to inspect
   * @return the celebrity followees, in no particular order
   */
  public List<String> celebrityFollowees(String userId) {
    List<String> celebrities = new ArrayList<>();
    Optional<String> cursor = Optional.empty();
    do {
      FollowRepository.Page page = follows.following(userId, cursor, FOLLOWING_SCAN_PAGE);
      users.findAllById(page.ids()).values().stream()
          .filter(UserItem::celebrity)
          .map(UserItem::userId)
          .forEach(celebrities::add);
      cursor = page.next();
    } while (cursor.isPresent());
    return List.copyOf(celebrities);
  }

  public boolean isFollowing(String followerId, String followeeId) {
    return follows.isFollowing(followerId, followeeId);
  }

  /**
   * A real bcrypt hash of a value nobody holds, compared against when the handle is unknown.
   *
   * <p>It has to be a well-formed hash of the same cost as the real ones. A constant like {@code
   * "x"} makes the encoder reject the format and return immediately, which reintroduces exactly the
   * timing difference this is here to remove.
   */
  private static final String DUMMY_HASH =
      "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
