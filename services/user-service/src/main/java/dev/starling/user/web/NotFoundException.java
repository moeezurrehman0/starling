/* SPDX-License-Identifier: MIT */
package dev.starling.user.web;

/** Nothing exists at the requested address. */
public class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NotFoundException() {
    // No message and no cause: nothing about why a lookup missed is safe to hand back, and a
    // stack trace for an ordinary 404 is noise in the logs of a service that will serve
    // millions of them.
    super(null, null, false, false);
  }
}
