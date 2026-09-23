/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.web;

/**
 * An {@code Idempotency-Key} was reused for a different request body.
 *
 * <p>Reported rather than honoured. Replaying the first request's result would answer a question
 * the client never asked, and performing the second under the first's key would make the key
 * meaningless — so the only safe response is to refuse and say why.
 */
public class IdempotencyConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param key the reused key
   */
  public IdempotencyConflictException(String key) {
    super("Idempotency-Key '" + key + "' was already used for a different request");
  }
}
