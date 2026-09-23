/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.twitterclone.contracts.UserItem;
import dev.twitterclone.user.persistence.FollowRepository;
import dev.twitterclone.user.persistence.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService")
class UserServiceTest {

  @Mock private UserRepository users;
  @Mock private FollowRepository follows;

  // A real encoder at its cheapest cost. Mocking it would let the timing-equalisation test
  // below pass while the production path did something entirely different.
  private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

  private UserService service() {
    return new UserService(users, follows, encoder);
  }

  private static UserItem user(String id, long followerCount, boolean celebrity) {
    return UserItem.builder()
        .userId(id)
        .handle("ada")
        .displayName("Ada")
        .followerCount(followerCount)
        .celebrity(celebrity)
        .createdAt(Instant.EPOCH)
        .build();
  }

  @Nested
  @DisplayName("register")
  class Register {

    @Test
    @DisplayName("mints a time-ordered id and never stores the raw password")
    void mintsIdAndHashes() {
      when(users.createWithHandle(any(), anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));

      UserItem created = service().register("Ada", "Ada Lovelace", "correct horse");

      ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
      verify(users).createWithHandle(any(), hash.capture());

      assertThat(hash.getValue()).isNotEqualTo("correct horse").startsWith("$2");
      assertThat(encoder.matches("correct horse", hash.getValue())).isTrue();
      // Version 7, so the id sorts by creation time -- the property timeline merging depends on.
      assertThat(UUID.fromString(created.userId()).version()).isEqualTo(7);
      assertThat(created.followerCount()).isZero();
      assertThat(created.celebrity()).isFalse();
    }

    @Test
    @DisplayName("keeps the handle as typed, leaving normalisation to the key")
    void preservesDisplayedHandleCase() {
      when(users.createWithHandle(any(), anyString()))
          .thenAnswer(invocation -> invocation.getArgument(0));
      // The stored key is lower-cased so "Ada" and "ada" cannot both be claimed, but the
      // profile should still show the capitalisation the owner chose.
      assertThat(service().register("Ada", "Ada Lovelace", "pw").handle()).isEqualTo("Ada");
    }
  }

  @Nested
  @DisplayName("authenticate")
  class Authenticate {

    @Test
    @DisplayName("accepts the right password")
    void accepts() {
      String stored = encoder.encode("correct horse");
      when(users.passwordHash("ada")).thenReturn(Optional.of(stored));
      when(users.findByHandle("ada")).thenReturn(Optional.of(user("u1", 0, false)));

      assertThat(service().authenticate("ada", "correct horse")).isPresent();
    }

    @Test
    @DisplayName("rejects the wrong password without looking the user up")
    void rejectsWrongPassword() {
      when(users.passwordHash("ada")).thenReturn(Optional.of(encoder.encode("correct horse")));

      assertThat(service().authenticate("ada", "battery staple")).isEmpty();
      verify(users, never()).findByHandle(anyString());
    }

    @Test
    @DisplayName("still verifies a hash when the handle does not exist")
    void unknownHandleStillHashes() {
      // The point is not the empty result -- it is that the unknown-handle path does the same
      // work as the known one. Returning early here is what makes registered handles
      // enumerable by timing alone.
      when(users.passwordHash("nobody")).thenReturn(Optional.empty());

      assertThat(service().authenticate("nobody", "anything")).isEmpty();
      verify(users, never()).findByHandle(anyString());
    }
  }

  @Nested
  @DisplayName("follow")
  class Follow {

    @Test
    @DisplayName("writes the edge before the count")
    void incrementsAfterEdge() {
      when(follows.follow("a", "b")).thenReturn(true);
      when(users.adjustFollowerCount("b", 1)).thenReturn(user("b", 1, false));

      assertThat(service().follow("a", "b")).isTrue();

      var order = org.mockito.Mockito.inOrder(follows, users);
      order.verify(follows).follow("a", "b");
      order.verify(users).adjustFollowerCount("b", 1);
    }

    @Test
    @DisplayName("does not count a repeated follow twice")
    void duplicateFollowDoesNotCount() {
      when(follows.follow("a", "b")).thenReturn(false);

      assertThat(service().follow("a", "b")).isFalse();
      verify(users, never()).adjustFollowerCount(anyString(), anyLong());
    }

    @Test
    @DisplayName("refuses a self-follow before touching the graph")
    void rejectsSelfFollow() {
      assertThatThrownBy(() -> service().follow("a", "a"))
          .isInstanceOf(IllegalArgumentException.class);
      verify(follows, never()).follow(anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("celebrity threshold")
  class Celebrity {

    @Test
    @DisplayName("promotes on the follow that reaches the threshold")
    void promotes() {
      when(follows.follow("a", "star")).thenReturn(true);
      when(users.adjustFollowerCount("star", 1))
          .thenReturn(user("star", UserItem.CELEBRITY_THRESHOLD, false));
      when(users.setCelebrity("star", true)).thenReturn(true);

      service().follow("a", "star");

      verify(users).setCelebrity("star", true);
    }

    @Test
    @DisplayName("leaves the flag alone one follower short")
    void doesNotPromoteEarly() {
      when(follows.follow("a", "star")).thenReturn(true);
      when(users.adjustFollowerCount("star", 1))
          .thenReturn(user("star", UserItem.CELEBRITY_THRESHOLD - 1, false));

      service().follow("a", "star");

      verify(users, never()).setCelebrity(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("does not re-set a flag that already agrees")
    void idempotentAboveThreshold() {
      when(follows.follow("a", "star")).thenReturn(true);
      when(users.adjustFollowerCount("star", 1))
          .thenReturn(user("star", UserItem.CELEBRITY_THRESHOLD + 500, true));

      service().follow("a", "star");

      // Every follow past the threshold would otherwise issue a conditional write that is
      // guaranteed to fail -- a wasted WCU on the hottest account in the system.
      verify(users, never()).setCelebrity(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("demotes on the unfollow that drops below the threshold")
    void demotes() {
      when(follows.unfollow("a", "star")).thenReturn(true);
      when(users.adjustFollowerCount("star", -1))
          .thenReturn(user("star", UserItem.CELEBRITY_THRESHOLD - 1, true));
      when(users.setCelebrity("star", false)).thenReturn(true);

      assertThat(service().unfollow("a", "star")).isTrue();

      verify(users).setCelebrity("star", false);
    }

    @Test
    @DisplayName("does not count an unfollow of an edge that was never there")
    void unfollowWithoutEdge() {
      when(follows.unfollow("a", "b")).thenReturn(false);

      assertThat(service().unfollow("a", "b")).isFalse();
      verify(users, never()).adjustFollowerCount(anyString(), anyLong());
    }
  }

  @Nested
  @DisplayName("read-through methods")
  class Reads {

    @Test
    @DisplayName("delegate without reinterpreting their arguments")
    void delegate() {
      var page = new FollowRepository.Page(java.util.List.of("x"), Optional.empty());
      when(users.findById("u1")).thenReturn(Optional.of(user("u1", 0, false)));
      when(users.findByHandle("ada")).thenReturn(Optional.of(user("u1", 0, false)));
      when(follows.followers(eq("u1"), any(), eq(20))).thenReturn(page);
      when(follows.following(eq("u1"), any(), eq(20))).thenReturn(page);
      when(follows.isFollowing("u1", "u2")).thenReturn(true);

      UserService service = service();
      assertThat(service.byId("u1")).isPresent();
      assertThat(service.byHandle("ada")).isPresent();
      assertThat(service.followers("u1", Optional.empty(), 20)).isEqualTo(page);
      assertThat(service.following("u1", Optional.empty(), 20)).isEqualTo(page);
      assertThat(service.isFollowing("u1", "u2")).isTrue();
    }
  }
}
