/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.web;

import dev.starling.timeline.domain.TimelineService;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The home timeline. */
@RestController
@RequestMapping("/v1")
public class TimelineController {

  /**
   * Hard ceiling on a page.
   *
   * <p>Unbounded would let one request ask for every entry a user has, and the merge holds both
   * halves in memory.
   */
  private static final int MAX_PAGE = 50;

  private final TimelineService timelines;

  public TimelineController(TimelineService timelines) {
    this.timelines = timelines;
  }

  /**
   * Reads the caller's own home timeline.
   *
   * <p>There is no {@code userId} parameter, and that absence is the authorisation rule. Taking the
   * owner from the path and checking it against the token would work, but it makes the check
   * something a future handler can forget; taking it from the token means an unauthorised read is
   * unrepresentable.
   *
   * @param principal the validated token
   * @param cursor exclusive cursor from a previous page
   * @param limit page size, capped
   * @return the page
   */
  @GetMapping("/timelines/home")
  public Api.TimelinePage home(
      @AuthenticationPrincipal Jwt principal,
      @RequestParam(required = false) @Nullable String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    if (limit < 1 || limit > MAX_PAGE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
    }
    TimelineService.Timeline timeline =
        timelines.home(principal.getSubject(), Optional.ofNullable(cursor), limit);
    return new Api.TimelinePage(timeline.tweets(), timeline.next().orElse(null));
  }
}
