/* SPDX-License-Identifier: MIT */
package dev.twitterclone.platform.aws.testing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.protocols.jsoncore.JsonNode;
import software.amazon.awssdk.protocols.jsoncore.JsonNodeParser;

/**
 * A LocalStack container with the project's real table bootstrap applied.
 *
 * <p>The container mounts {@code tools/} and {@code tools/localstack/ready.d} at exactly the paths
 * {@code compose.yaml} uses, so these tests run against the same script, reading the same
 * definitions file, as {@code make up}. Creating the tables from a bespoke test helper instead
 * would mean the suite could pass against a key schema nothing else in the repo produces -- which
 * is the specific failure this project keeps finding and fixing.
 *
 * <p>One container is shared by every test class in the JVM. Starting LocalStack costs several
 * seconds, so suites are expected to use distinct ids rather than to depend on an empty table.
 *
 * <p>This lives in a test-fixtures source set rather than being copied into each service. A second
 * copy would have drifted: the bootstrap-completion check below is subtle enough that a divergent
 * copy would most likely lose it and start failing intermittently on a cold container.
 */
public final class LocalStack {

  private LocalStack() {}

  private static final DockerImageName IMAGE =
      // Pinned to the same tag as compose.yaml. A floating tag would let the local stack and
      // the test suite drift onto different DynamoDB implementations without anyone noticing.
      DockerImageName.parse("localstack/localstack:4.9");

  private static final LocalStackContainer CONTAINER = start();

  private static LocalStackContainer start() {
    Path tools = repoRoot().resolve("tools");

    LocalStackContainer container =
        new LocalStackContainer(IMAGE)
            .withServices("dynamodb", "dynamodbstreams")
            // Selects the DynamoDB-Local-backed provider rather than the legacy in-memory one.
            // compose.yaml sets the same flag; stream fidelity is the one thing this project
            // genuinely relies on LocalStack getting right.
            .withEnv("DYNAMODB_PROVIDER_V2", "1")
            .withCopyFileToContainer(MountableFile.forHostPath(tools), "/opt/twitter-tools")
            .withCopyFileToContainer(
                MountableFile.forHostPath(tools.resolve("localstack/ready.d"), 0777),
                "/etc/localstack/init/ready.d");

    container.start();
    awaitBootstrap(container);
    return container;
  }

  /**
   * Blocks until the READY stage has run and every script in it reported success.
   *
   * <p>Not a Testcontainers wait strategy. The port opening only proves LocalStack is up, a log
   * line cannot mark completion because the hook's last act is to {@code exec} python3, and the
   * built-in HTTP strategy polls hard enough during boot to catch the edge port mid-handshake and
   * fail on a truncated response. Polling here instead also means a bootstrap failure is reported
   * with the endpoint's own explanation rather than as a container startup timeout.
   *
   * <p>The rule is the one {@code tools/localstack/healthcheck.py} applies, for the same reason:
   * {@code completed.READY} reports that the stage finished, not that its scripts succeeded, and
   * goes true even when a hook exits non-zero.
   */
  private static void awaitBootstrap(LocalStackContainer container) {
    URI endpoint = container.getEndpoint().resolve("/_localstack/init");
    HttpClient http = HttpClient.newHttpClient();
    Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
    String last = "no response";

    while (Instant.now().isBefore(deadline)) {
      try {
        HttpResponse<String> response =
            http.send(
                HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).build(),
                HttpResponse.BodyHandlers.ofString());
        last = response.body();
        if (response.statusCode() == 200 && bootstrapSucceeded(last)) {
          return;
        }
      } catch (IOException e) {
        last = e.toString();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted waiting for LocalStack bootstrap", e);
      }
      try {
        Thread.sleep(Duration.ofMillis(500));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted waiting for LocalStack bootstrap", e);
      }
    }
    throw new IllegalStateException(
        "LocalStack table bootstrap did not complete. Last /_localstack/init response: " + last);
  }

  /**
   * True once the READY stage has run and every script in it reported success.
   *
   * <p>The empty-list check matters: {@code allMatch} over no scripts is vacuously true, so a
   * container that mounted the hooks at the wrong path would look ready and every test would then
   * fail on a missing table rather than on the real cause.
   */
  private static boolean bootstrapSucceeded(String body) {
    JsonNode root = JsonNodeParser.create().parse(body);
    JsonNode ready = root.asObject().get("completed").asObject().get("READY");
    if (ready == null || !ready.asBoolean()) {
      return false;
    }
    List<JsonNode> scripts = root.asObject().get("scripts").asArray();
    return !scripts.isEmpty()
        && scripts.stream()
            .allMatch(script -> "SUCCESSFUL".equals(script.asObject().get("state").asString()));
  }

  /**
   * The one container, started on first use and reused by every suite in the JVM.
   *
   * @return the running LocalStack container
   */
  public static LocalStackContainer container() {
    return CONTAINER;
  }

  /**
   * Walks up from the working directory to the repository root.
   *
   * <p>Gradle runs tests with the module directory as the working directory, but the table
   * definitions live at the root and are shared with Terraform. Resolving by marker rather than by
   * a fixed number of {@code ..} segments keeps this working if the module ever moves.
   */
  private static Path repoRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))) {
      candidate = candidate.getParent();
    }
    if (candidate == null) {
      throw new IllegalStateException("Could not locate the repository root from " + Path.of(""));
    }
    return candidate;
  }
}
