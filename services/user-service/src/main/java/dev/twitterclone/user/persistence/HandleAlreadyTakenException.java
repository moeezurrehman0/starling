/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.persistence;

/** Raised when a registration loses the race to claim a handle. */
public class HandleAlreadyTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String handle;

  public HandleAlreadyTakenException(String handle, Throwable cause) {
    super("handle already taken: " + handle, cause);
    this.handle = handle;
  }

  public String handle() {
    return handle;
  }
}
