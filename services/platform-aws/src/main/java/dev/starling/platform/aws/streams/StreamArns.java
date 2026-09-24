/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

/**
 * Finds the current stream of a table.
 *
 * <p>A DynamoDB stream ARN ends in the timestamp at which streaming was enabled, so it is not
 * derivable from the table name and it changes whenever streams are disabled and re-enabled. That
 * makes it awkward to configure: in the sandbox it is a Terraform output, but locally LocalStack
 * mints a fresh one every time the stack is recreated, and a hard-coded value would be wrong after
 * the first {@code make down-hard}.
 *
 * <p>Consumers therefore accept a blank ARN and ask here instead. An explicitly configured ARN
 * always wins — discovery is the fallback, not the default, because in a real account the table a
 * service can describe and the stream it is meant to consume are not always the same decision.
 */
public final class StreamArns {

  private static final Logger LOG = LoggerFactory.getLogger(StreamArns.class);

  private final DynamoDbClient dynamo;

  /**
   * Creates a resolver.
   *
   * @param dynamo the control-plane client used for {@code DescribeTable}
   */
  public StreamArns(DynamoDbClient dynamo) {
    this.dynamo = dynamo;
  }

  /**
   * The configured ARN, or the table's current one when the configuration is blank.
   *
   * <p>Never throws. A missing table, a denied {@code DescribeTable} or an unreachable endpoint all
   * yield an empty result, which every consumer already treats as "no stream configured" and
   * handles by idling. A service that cannot find a stream should still serve HTTP.
   *
   * @param configured the ARN from configuration, possibly blank
   * @param tableName the table whose stream is wanted
   * @return the ARN to consume, or empty when there is none
   */
  public Optional<String> resolve(String configured, String tableName) {
    return resolve(configured, tableName, true);
  }

  /**
   * A lazily-resolving, retrying handle on a table's stream.
   *
   * <p>Prefer this to {@link #resolve} anywhere the result is needed for the life of the process.
   * {@code resolve} answers once; if that one attempt fails — and at start-up it is exactly as
   * likely to fail as any other first call to a dependency — the caller is left holding an empty
   * result forever. A {@link StreamSource} retries until it succeeds and can say whether it has.
   *
   * @param configured the ARN from configuration, possibly blank
   * @param tableName the table whose stream is wanted
   * @return a handle that resolves on first use and caches only success
   */
  public StreamSource source(String configured, String tableName) {
    return new StreamSource(this, configured, tableName);
  }

  /**
   * As {@link #resolve(String, String)}, with control over failure logging.
   *
   * @param configured the ARN from configuration, possibly blank
   * @param tableName the table whose stream is wanted
   * @param logFailure whether a failed discovery should be logged at {@code WARN}
   * @return the ARN to consume, or empty when there is none
   */
  public Optional<String> resolve(String configured, String tableName, boolean logFailure) {
    if (configured != null && !configured.isBlank()) {
      return Optional.of(configured);
    }
    try {
      TableDescription table =
          dynamo.describeTable(DescribeTableRequest.builder().tableName(tableName).build()).table();
      String discovered = table.latestStreamArn();
      if (discovered == null || discovered.isBlank()) {
        // The table exists but has no stream. Almost always a table created without a
        // StreamSpecification, which is a deployment bug rather than a transient fault, so it
        // is worth a warning even though it is not fatal.
        if (logFailure) {
          LOG.warn("table {} has no stream enabled; nothing to consume", tableName);
        }
        return Optional.empty();
      }
      LOG.info("discovered stream {} for table {}", discovered, tableName);
      return Optional.of(discovered);
    } catch (RuntimeException e) {
      if (logFailure) {
        LOG.warn("could not discover the stream for table {}: {}", tableName, e.toString());
      }
      return Optional.empty();
    }
  }
}
