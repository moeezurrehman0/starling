/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.web;

/** A credential that verified but can no longer be honoured. */
public class UnauthenticatedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnauthenticatedException(String message) {
    super(message);
  }
}
