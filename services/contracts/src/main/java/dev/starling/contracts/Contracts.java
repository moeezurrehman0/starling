/* SPDX-License-Identifier: MIT */
package dev.starling.contracts;

import org.jspecify.annotations.Nullable;

/**
 * Small helpers shared by the item builders in this package.
 *
 * <p>Deliberately package-private in spirit: it exists so that a builder can reject an incomplete
 * item at the point of construction rather than emitting a record with null in a field the type
 * system has declared non-null. The enhanced client builds items by calling setters one at a time,
 * so there is no other point at which completeness can be checked.
 */
public final class Contracts {

  private Contracts() {}

  /**
   * Returns {@code value}, or throws if it was never set.
   *
   * @throws IllegalStateException naming the missing attribute, because the alternative is a {@code
   *     NullPointerException} from deep inside the mapper with no indication of which attribute was
   *     absent from the stored item
   */
  public static <T> T required(@Nullable T value, String attribute) {
    if (value == null) {
      throw new IllegalStateException("missing required attribute: " + attribute);
    }
    return value;
  }
}
