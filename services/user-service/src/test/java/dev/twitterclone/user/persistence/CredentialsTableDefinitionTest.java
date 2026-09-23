/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.protocols.jsoncore.JsonNode;
import software.amazon.awssdk.protocols.jsoncore.JsonNodeParser;

/**
 * Checks {@link CredentialItem#SCHEMA} against {@code tools/dynamodb-tables.json}.
 *
 * <p>{@code credentials} is the one table with no schema in {@code services/contracts}, so it is
 * also the one table the cross-check there does not cover. Without this test the mapping would be
 * verified only against itself, and a renamed attribute would deploy cleanly and then read back
 * nothing -- which on a login path means every password appearing wrong at once.
 */
@DisplayName("credentials table definition")
class CredentialsTableDefinitionTest {

  private static final String TABLE = "credentials";

  private static JsonNode spec() {
    String configured = System.getProperty("dynamodb.tables.file");
    assertThat(configured)
        .as("system property dynamodb.tables.file; see this module's build.gradle.kts")
        .isNotNull();
    try {
      JsonNode root = JsonNodeParser.create().parse(Files.readString(Path.of(configured)));
      return root.asObject().get("tables").asObject().get(TABLE);
    } catch (Exception e) {
      throw new IllegalStateException("Could not read table definitions at " + configured, e);
    }
  }

  @Test
  @DisplayName("is declared, and declared as privately owned by this service")
  void isPrivatelyOwned() {
    // The owner marker is what excludes this table from the contracts cross-check. Dropping it
    // makes that check fail for a missing schema, which is the right failure -- this asserts
    // the arrangement from the side that benefits from it.
    assertThat(spec()).as("tables.%s", TABLE).isNotNull();
    assertThat(spec().asObject().get("owner").asString()).isEqualTo("user-service");
  }

  @Test
  @DisplayName("agrees with the mapped key schema")
  void matchesSchema() {
    JsonNode table = spec();
    var metadata = CredentialItem.SCHEMA.tableMetadata();

    assertThat(metadata.primaryPartitionKey())
        .isEqualTo(table.asObject().get("hash_key").asString());
    assertThat(metadata.primarySortKey()).isEmpty();
    assertThat(table.asObject().get("range_key")).isNull();

    // Only key attributes appear in the JSON -- DynamoDB is schemaless about the rest -- so the
    // assertion is that every declared attribute is one the mapper actually produces.
    Map<String, JsonNode> declared = table.asObject().get("attributes").asObject();
    assertThat(CredentialItem.SCHEMA.attributeNames()).containsAll(declared.keySet());
  }

  @Test
  @DisplayName("stores the hash under the abbreviated names this service reads back")
  void storedAttributeNames() {
    CredentialItem item =
        CredentialItem.builder()
            .handle("ada")
            .passwordHash("$2a$10$hash")
            .updatedAt(Instant.parse("2025-01-01T00:00:00Z"))
            .build();

    Map<String, ?> stored = CredentialItem.SCHEMA.itemToMap(item, false);
    assertThat(stored).containsOnlyKeys("h", "ph", "ua");
    assertThat(CredentialItem.SCHEMA.mapToItem(CredentialItem.SCHEMA.itemToMap(item, false)))
        .isEqualTo(item);
  }

  @Test
  @DisplayName("keeps the hash out of toString")
  void toStringRedactsTheHash() {
    // A record's generated toString prints every component, and this object reaches a log line
    // the moment anything logs a repository result or an exception carrying one.
    CredentialItem item =
        CredentialItem.builder()
            .handle("ada")
            .passwordHash("$2a$10$secret")
            .updatedAt(Instant.EPOCH)
            .build();

    assertThat(item.toString()).doesNotContain("secret").contains("ada", "***");
  }
}
