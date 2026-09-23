/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.web;

/** A resource named in the path does not exist. */
public class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param kind what was being looked for
   * @param id the identifier that found nothing
   */
  public NotFoundException(String kind, String id) {
    super("No such " + kind + ": " + id);
  }
}
