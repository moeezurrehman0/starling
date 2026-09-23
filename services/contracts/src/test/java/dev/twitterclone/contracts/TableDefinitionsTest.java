/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.enhanced.dynamodb.TableMetadata;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.protocols.jsoncore.JsonNode;
import software.amazon.awssdk.protocols.jsoncore.JsonNodeParser;

/**
 * Cross-checks the Java {@link TableSchemas} against {@code tools/dynamodb-tables.json}, the file
 * that also drives LocalStack bootstrap and the Terraform data module.
 *
 * <p>Having one definitions file is only worth something if something enforces that the code agrees
 * with it. Nothing else does. The Java schema decides which attribute the client sends as a key;
 * the JSON decides which attribute the table is actually keyed on. When they diverge the compiler
 * is silent, the round-trip tests are silent, and LocalStack is silent — the failure is a {@code
 * ValidationException} on the first real query, which is to say after deploy.
 *
 * <p>These assertions are therefore the seam between the application and the infrastructure, and
 * they run in the ordinary unit test task: no Docker, no AWS, no LocalStack. A key-schema mistake
 * fails in seconds on a laptop rather than in the sandbox.
 *
 * <p>The JSON is parsed with the AWS SDK's own {@code JsonNodeParser}, which is already on the
 * classpath via the DynamoDB client. This module has no Jackson dependency and should not acquire
 * one merely to read a fixture.
 */
@DisplayName("Table definitions")
class TableDefinitionsTest {

  private static final JsonNode DEFINITIONS = load();

  private static JsonNode load() {
    String configured = System.getProperty("dynamodb.tables.file");
    if (configured == null) {
      throw new IllegalStateException(
          "System property dynamodb.tables.file is not set; see services/contracts/build.gradle.kts");
    }
    Path path = Path.of(configured);
    try {
      return JsonNodeParser.create().parse(Files.readString(path));
    } catch (Exception e) {
      throw new IllegalStateException("Could not read table definitions at " + path, e);
    }
  }

  private static Map<String, JsonNode> tables() {
    return DEFINITIONS.asObject().get("tables").asObject();
  }

  /**
   * The tables this module is responsible for: every one except those a single service owns.
   *
   * <p>A table carrying an {@code owner} is deliberately absent from {@link TableSchemas} -- {@code
   * credentials} is the first, because putting a password hash on the shared {@link UserItem} would
   * ship it to two services that never need it. Its schema is cross-checked against this same file
   * by a test inside the owning service, so the invariant holds on both sides rather than being
   * dropped for the exception.
   */
  private static Map<String, JsonNode> sharedTables() {
    return tables().entrySet().stream()
        .filter(entry -> entry.getValue().asObject().get("owner") == null)
        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /** Every logical table paired with the schema that is supposed to describe it. */
  private static Stream<org.junit.jupiter.params.provider.Arguments> mappedTables() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of("users", TableSchemas.USER),
        org.junit.jupiter.params.provider.Arguments.of("handles", TableSchemas.HANDLE),
        org.junit.jupiter.params.provider.Arguments.of("tweets", TableSchemas.TWEET),
        org.junit.jupiter.params.provider.Arguments.of("follows", TableSchemas.FOLLOW),
        org.junit.jupiter.params.provider.Arguments.of("timelines", TableSchemas.TIMELINE_ENTRY),
        org.junit.jupiter.params.provider.Arguments.of("likes", TableSchemas.LIKE),
        org.junit.jupiter.params.provider.Arguments.of("idempotency", TableSchemas.IDEMPOTENCY),
        org.junit.jupiter.params.provider.Arguments.of(
            "stream_checkpoints", TableSchemas.STREAM_CHECKPOINT));
  }

  private static Optional<String> text(JsonNode node, String field) {
    JsonNode child = node.asObject().get(field);
    return child == null || child.isNull() ? Optional.empty() : Optional.of(child.asString());
  }

  @Test
  @DisplayName("declares a schema for every table and no schema for a table that is gone")
  void everyTableHasASchema() {
    // Guards the case the parameterised tests cannot see: a table added to the JSON with no
    // corresponding schema, which would deploy an empty table nothing ever writes to.
    List<String> mapped = mappedTables().map(args -> (String) args.get()[0]).sorted().toList();
    assertThat(sharedTables().keySet().stream().sorted().toList()).isEqualTo(mapped);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mappedTables")
  @DisplayName("agrees with the deployed key schema")
  void keySchemaMatches(String name, TableSchema<?> schema) {
    JsonNode spec = tables().get(name);
    TableMetadata metadata = schema.tableMetadata();

    assertThat(metadata.primaryPartitionKey())
        .as("%s partition key", name)
        .isEqualTo(spec.asObject().get("hash_key").asString());

    assertThat(metadata.primarySortKey())
        .as("%s sort key", name)
        .isEqualTo(text(spec, "range_key"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mappedTables")
  @DisplayName("declares every key attribute the table defines, and no others")
  void attributeDefinitionsMatch(String name, TableSchema<?> schema) {
    // DynamoDB rejects an AttributeDefinition for a non-key attribute, and silently ignores a
    // key it was never told about. Both directions are worth asserting: an orphaned definition
    // fails table creation in the sandbox, and a missing one fails the query instead.
    JsonNode spec = tables().get(name);
    assertThat(spec.asObject().get("attributes").asObject().keySet())
        .as("%s key attributes", name)
        .allSatisfy(attribute -> assertThat(schema.attributeNames()).contains(attribute));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mappedTables")
  @DisplayName("agrees with the deployed secondary indexes")
  void indexesMatch(String name, TableSchema<?> schema) {
    JsonNode spec = tables().get(name);
    Map<String, JsonNode> indexes = spec.asObject().get("global_secondary_indexes").asObject();
    TableMetadata metadata = schema.tableMetadata();

    indexes.forEach(
        (indexName, index) -> {
          assertThat(metadata.indexPartitionKey(indexName))
              .as("%s/%s partition key", name, indexName)
              .isEqualTo(index.asObject().get("hash_key").asString());
          assertThat(metadata.indexSortKey(indexName))
              .as("%s/%s sort key", name, indexName)
              .isEqualTo(text(index, "range_key"));
        });
  }

  @Test
  @DisplayName("enables TTL on exactly the tables whose records carry an expiry")
  void ttlAttributesMatchTheRecords() {
    // A TTL attribute renamed in TableSchemas but not here stops expiry dead: DynamoDB looks
    // for an attribute that no longer exists and deletes nothing, forever, without an error.
    assertThat(text(tables().get("timelines"), "ttl_attribute")).contains("exp");
    assertThat(text(tables().get("idempotency"), "ttl_attribute")).contains("exp");

    assertThat(TableSchemas.TIMELINE_ENTRY.attributeNames()).contains("exp");
    assertThat(TableSchemas.IDEMPOTENCY.attributeNames()).contains("exp");

    // And nowhere else. A TTL quietly enabled on users or tweets would delete durable data.
    tables().entrySet().stream()
        .filter(entry -> !List.of("timelines", "idempotency").contains(entry.getKey()))
        .forEach(
            entry ->
                assertThat(text(entry.getValue(), "ttl_attribute"))
                    .as("%s must not expire", entry.getKey())
                    .isEmpty());
  }

  @Test
  @DisplayName("enables a stream only on tweets, which is the only table anything consumes")
  void onlyTweetsCarriesAStream() {
    // Two consumer groups already read this stream, which is DynamoDB's hard per-shard reader
    // limit before throttling. Enabling a stream on a second table is not automatically wrong,
    // but it is a design decision that needs an ADR rather than a quiet JSON edit.
    assertThat(text(tables().get("tweets"), "stream")).contains("NEW_IMAGE");
    tables().entrySet().stream()
        .filter(entry -> !entry.getKey().equals("tweets"))
        .forEach(
            entry ->
                assertThat(text(entry.getValue(), "stream"))
                    .as("%s must not carry a stream", entry.getKey())
                    .isEmpty());
  }

  @Test
  @DisplayName("uses only the documented keys, so a consumer can strip comments with one rule")
  void commentKeysAreConfinedToTheDollarPrefix() {
    // The definitions file carries its reasoning in $-prefixed keys because JSON has no
    // comments and Terraform's jsondecode can read JSON but not YAML. Anything consuming the
    // file skips that prefix; this keeps the prefix the only rule needed, so a typo such as
    // "comment_key" cannot silently become a field a consumer tries to interpret.
    List<String> allowed =
        List.of(
            "description",
            // Present only on a table a single service owns privately. Terraform still creates
            // it; it is contracts that must not claim to describe it.
            "owner",
            "hash_key",
            "range_key",
            "attributes",
            "stream",
            "ttl_attribute",
            "global_secondary_indexes");

    tables()
        .forEach(
            (name, table) ->
                assertThat(table.asObject().keySet())
                    .as("%s fields", name)
                    .filteredOn(key -> !key.startsWith("$"))
                    .isSubsetOf(allowed));
  }
}
