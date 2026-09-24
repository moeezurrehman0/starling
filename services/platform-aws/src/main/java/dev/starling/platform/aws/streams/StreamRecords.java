/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;

/** Reading the parts of a stream record that consumers actually use. */
public final class StreamRecords {

  private StreamRecords() {}

  /**
   * The new image of an {@code INSERT}, if the record is one.
   *
   * <p>Both consumers on the {@code tweets} stream want inserts only. {@code MODIFY} fires on every
   * like, because the like counter lives on the tweet row: acting on those would rewrite every
   * follower's timeline each time anyone tapped a heart, turning a single-item update into millions
   * of writes.
   *
   * @param record the stream record
   * @return the new image, or empty if the record is not an insert or carries no image
   */
  public static Optional<Map<String, AttributeValue>> insertImage(Record record) {
    if (record.eventName() != OperationType.INSERT) {
      return Optional.empty();
    }
    Map<String, AttributeValue> image = record.dynamodb().newImage();
    // Empty as well as null. The SDK auto-constructs absent maps, so a record carrying no
    // image arrives as an empty one rather than a null, and a caller checking only for null
    // would go on to read every attribute as missing.
    return image == null || image.isEmpty() ? Optional.empty() : Optional.of(image);
  }

  /**
   * A string attribute, or null.
   *
   * @param image the record image
   * @param attribute the attribute name
   * @return the value, or null if absent or not a string
   */
  public static @Nullable String string(Map<String, AttributeValue> image, String attribute) {
    AttributeValue value = image.get(attribute);
    return value == null ? null : value.s();
  }
}
