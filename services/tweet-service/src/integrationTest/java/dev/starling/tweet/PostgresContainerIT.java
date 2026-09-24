/* SPDX-License-Identifier: MIT */
package dev.starling.tweet;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the integrationTest source set can start a real dependency. Every persistence test in this
 * repository runs against PostgreSQL rather than an in-memory substitute, so migrations are
 * exercised before they reach a cluster.
 */
@Testcontainers
class PostgresContainerIT {

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @Test
  void containerServesQueries() throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("select version()")) {

      assertThat(result.next()).isTrue();
      assertThat(result.getString(1)).contains("PostgreSQL");
    }
  }
}
