/* SPDX-License-Identifier: MIT */
package dev.twitterclone.timeline.web;

import dev.twitterclone.timeline.domain.TweetView;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Request and response bodies for the timeline API. */
public final class Api {

  private Api() {}

  /**
   * One page of a home timeline.
   *
   * @param items the tweets, newest first
   * @param nextCursor opaque cursor for the next page, absent at the end
   */
  public record TimelinePage(List<TweetView> items, @Nullable String nextCursor) {}
}
