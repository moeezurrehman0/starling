/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * A stream ARN that resolves when it is first needed and keeps trying until it does.
 *
 * <p>This exists because of a failure that reached a running cluster. Discovery used to happen once
 * in a consumer's constructor: {@code arns.resolve(...).orElse("")}. When that single call timed
 * out — LocalStack was unreachable for the few seconds before a NetworkPolicy was corrected — the
 * consumer was built with a blank ARN, logged one {@code WARN}, started its loop, passed both
 * probes and sat at {@code 1/1 Running} consuming nothing. Tweets returned 201 and rows landed;
 * timelines simply stayed empty. The only evidence was an absence, and no restart was ever
 * triggered because nothing was down.
 *
 * <p>Two properties fix that, and both matter:
 *
 * <ul>
 *   <li><b>Retry.</b> Resolution is attempted on every use until it succeeds. A dependency that is
 *       not up yet is the normal case at start-up, not an error, and a consumer that gives up on
 *       the first attempt has made a start-up ordering problem permanent.
 *   <li><b>Observability.</b> {@link #resolved()} lets readiness reflect reality, so the pod is not
 *       marked ready while it is incapable of doing the one thing it exists to do.
 * </ul>
 *
 * <p>Only success is cached. A resolved ARN is stable for the life of a stream, and re-describing
 * the table on every poll would add a control-plane call per tick to the data path.
 *
 * <p>Failures are logged on the first attempt and then only every {@value #LOG_EVERY} attempts. At
 * the idle backoff that is roughly one line a minute rather than one per poll: frequent enough that
 * the condition is visible in a log search, rare enough that it does not bury everything else.
 *
 * <p>Thread-safe. One consumer thread calls {@link #get}; the actuator's thread calls {@link
 * #resolved()} concurrently.
 */
public final class StreamSource implements Supplier<String> {

  /** How many failed attempts pass between log lines after the first. */
  static final long LOG_EVERY = 30;

  private final StreamArns arns;
  private final String configured;
  private final String tableName;
  private final AtomicLong attempts = new AtomicLong();
  private volatile String resolved = "";

  StreamSource(StreamArns arns, String configured, String tableName) {
    this.arns = arns;
    this.configured = configured;
    this.tableName = tableName;
  }

  /**
   * The ARN, resolving it if this is the first successful call.
   *
   * <p>Never throws, and never blocks beyond one {@code DescribeTable}. Returns blank while the
   * stream is undiscoverable, which every reader already treats as "nothing to consume".
   *
   * @return the stream ARN, or blank when it is not known yet
   */
  @Override
  public String get() {
    String current = resolved;
    if (!current.isBlank()) {
      return current;
    }
    long attempt = attempts.incrementAndGet();
    boolean logFailure = attempt == 1 || attempt % LOG_EVERY == 0;
    Optional<String> found = arns.resolve(configured, tableName, logFailure);
    found.ifPresent(arn -> resolved = arn);
    return found.orElse("");
  }

  /**
   * Whether the ARN is known.
   *
   * <p>Deliberately does not attempt resolution. A health check that performs I/O turns a slow
   * dependency into a failing probe, and the probe timeout then decides whether the pod restarts.
   *
   * @return true once the stream has been found
   */
  public boolean resolved() {
    return !resolved.isBlank();
  }

  /**
   * The table whose stream this is.
   *
   * @return the table name
   */
  public String tableName() {
    return tableName;
  }

  /**
   * How many resolution attempts have been made.
   *
   * @return the attempt count, which stops rising once resolution succeeds
   */
  public long attempts() {
    return attempts.get();
  }
}
