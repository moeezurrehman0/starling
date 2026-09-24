/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The search index against a real Postgres.
 *
 * <p>Nothing here can honestly be unit-tested. Every property that matters — that {@code
 * websearch_to_tsquery} survives whatever a user types, that stemming makes "running" match "run",
 * that a keyset cursor pages without gaps or repeats, that the upsert is genuinely idempotent — is
 * a property of the database and of the migration, not of the Java wrapping them. A mocked {@code
 * JdbcClient} would assert only that the class calls the method it calls.
 *
 * <p>The migration is run by Flyway here, exactly as it is at startup, so the test covers the SQL
 * in {@code V1__search_index.sql} too: a generated column or a missing index is precisely the kind
 * of mistake a hand-built schema in a test would hide.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIndexIntegrationTest {

  private static final Instant NOW = Instant.parse("2025-03-01T12:00:00Z");

  // org.testcontainers.postgresql, not org.testcontainers.containers: Testcontainers 2.x moved
  // every module's container into its own package and deprecated the old location.
  @SuppressWarnings("resource")
  private final PostgreSQLContainer postgres =
      new PostgreSQLContainer("postgres:18-alpine")
          .withDatabaseName("starling_search")
          .withUsername("starling")
          .withPassword("starling");

  private SearchIndex index;
  private JdbcClient jdbc;

  @BeforeAll
  void start() {
    postgres.start();

    PGSimpleDataSource source = new PGSimpleDataSource();
    source.setUrl(postgres.getJdbcUrl());
    source.setUser(postgres.getUsername());
    source.setPassword(postgres.getPassword());

    migrate(source);
    this.jdbc = JdbcClient.create(source);
    this.index = new SearchIndex(jdbc);
  }

  private static void migrate(DataSource source) {
    Flyway.configure()
        .dataSource(source)
        .schemas("search")
        .defaultSchema("search")
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  @BeforeEach
  void clean() {
    jdbc.sql("TRUNCATE search.tweet").update();
  }

  private void given(String id, String body, Instant at) {
    index.index(id, "author-1", body, at);
  }

  private List<String> ids(List<SearchIndex.Hit> hits) {
    return hits.stream().map(SearchIndex.Hit::tweetId).toList();
  }

  @Nested
  @DisplayName("matching")
  class Matching {

    @Test
    @DisplayName("finds a tweet by a word in it")
    void findsByWord() {
      given("t1", "the coffee is excellent here", NOW);

      assertThat(ids(index.search("coffee", 10, null))).containsExactly("t1");
    }

    @Test
    @DisplayName("stems, so a search for one form finds another")
    void stems() {
      given("t1", "I am running late", NOW);

      assertThat(ids(index.search("run", 10, null))).containsExactly("t1");
    }

    @Test
    @DisplayName("is case-insensitive")
    void caseInsensitive() {
      given("t1", "Kubernetes is fine", NOW);

      assertThat(ids(index.search("KUBERNETES", 10, null))).containsExactly("t1");
    }

    @Test
    @DisplayName("ANDs the terms of a multi-word query")
    void andsTerms() {
      given("t1", "coffee and cake", NOW);
      given("t2", "coffee alone", NOW.minusSeconds(1));

      assertThat(ids(index.search("coffee cake", 10, null))).containsExactly("t1");
    }

    @Test
    @DisplayName("honours a quoted phrase")
    void phrase() {
      given("t1", "a flat white please", NOW);
      given("t2", "white flat tyre", NOW.minusSeconds(1));

      assertThat(ids(index.search("\"flat white\"", 10, null))).containsExactly("t1");
    }

    @Test
    @DisplayName("honours negation")
    void negation() {
      given("t1", "coffee and cake", NOW);
      given("t2", "coffee and rain", NOW.minusSeconds(1));

      assertThat(ids(index.search("coffee -cake", 10, null))).containsExactly("t2");
    }

    @Test
    @DisplayName("survives input that would make to_tsquery throw")
    void hostileInput() {
      given("t1", "anything", NOW);

      // Each of these is a syntax error for to_tsquery and therefore a 500 in any service
      // that uses it. websearch_to_tsquery cannot be made to throw, which is the whole reason
      // it is the one in the query.
      assertThat(index.search("&&&", 10, null)).isEmpty();
      assertThat(index.search("unbalanced \" quote", 10, null)).isEmpty();
      assertThat(index.search("!", 10, null)).isEmpty();
      assertThat(index.search("a & | b", 10, null)).isEmpty();
    }

    @Test
    @DisplayName("finds nothing for a term nobody wrote")
    void noMatches() {
      given("t1", "coffee", NOW);

      assertThat(index.search("tractor", 10, null)).isEmpty();
    }
  }

  @Nested
  @DisplayName("writing")
  class Writing {

    @Test
    @DisplayName("indexing the same tweet twice leaves one row, so replays are free")
    void upsertIsIdempotent() {
      given("t1", "original", NOW);
      given("t1", "original", NOW);

      assertThat(index.search("original", 10, null)).hasSize(1);
    }

    @Test
    @DisplayName("re-indexing replaces the text, and the generated document with it")
    void upsertReplacesText() {
      // Deliberately not "before" and "after": both are English stop words, so neither is in
      // the tsvector and the test would pass for the wrong reason.
      given("t1", "espresso", NOW);
      given("t1", "cortado", NOW);

      assertThat(index.search("espresso", 10, null)).isEmpty();
      assertThat(ids(index.search("cortado", 10, null))).containsExactly("t1");
    }

    @Test
    @DisplayName("removes a tweet from the index")
    void removes() {
      given("t1", "coffee", NOW);

      assertThat(index.remove("t1")).isTrue();
      assertThat(index.search("coffee", 10, null)).isEmpty();
    }

    @Test
    @DisplayName("removing an unindexed tweet is a no-op, not an error")
    void removeAbsent() {
      assertThat(index.remove("never-existed")).isFalse();
    }

    @Test
    @DisplayName("round-trips the author and the timestamp")
    void roundTripsFields() {
      Instant at = NOW.truncatedTo(ChronoUnit.MILLIS);
      index.index("t1", "author-9", "hello", at);

      SearchIndex.Hit hit = index.search("hello", 10, null).getFirst();

      assertThat(hit.authorId()).isEqualTo("author-9");
      assertThat(hit.body()).isEqualTo("hello");
      assertThat(hit.createdAt()).isEqualTo(at);
    }
  }

  @Nested
  @DisplayName("paging")
  class Paging {

    @BeforeEach
    void threeMatches() {
      given("t1", "coffee one", NOW);
      given("t2", "coffee two", NOW.minusSeconds(60));
      given("t3", "coffee three", NOW.minusSeconds(120));
    }

    @Test
    @DisplayName("returns newest first")
    void newestFirst() {
      assertThat(ids(index.search("coffee", 10, null))).containsExactly("t1", "t2", "t3");
    }

    @Test
    @DisplayName("honours the page size")
    void honoursLimit() {
      assertThat(ids(index.search("coffee", 2, null))).containsExactly("t1", "t2");
    }

    @Test
    @DisplayName("clamps a limit above the ceiling")
    void clampsLimit() {
      assertThat(index.search("coffee", 10_000, null)).hasSize(3);
    }

    @Test
    @DisplayName("clamps a nonsensical limit rather than producing a SQL error")
    void clampsZero() {
      assertThat(index.search("coffee", 0, null)).hasSize(1);
    }

    @Test
    @DisplayName("resumes after the cursor, with no gap and no repeat")
    void resumes() {
      List<SearchIndex.Hit> first = index.search("coffee", 2, null);
      SearchIndex.Hit last = first.getLast();

      List<SearchIndex.Hit> second =
          index.search("coffee", 2, new SearchIndex.Cursor(last.createdAt(), last.tweetId()));

      assertThat(ids(second)).containsExactly("t3");
    }

    @Test
    @DisplayName("breaks a timestamp tie by id, so neither row is skipped or repeated")
    void tieBreaksById() {
      jdbc.sql("TRUNCATE search.tweet").update();
      given("a", "coffee", NOW);
      given("b", "coffee", NOW);
      given("c", "coffee", NOW);

      List<SearchIndex.Hit> first = index.search("coffee", 2, null);
      SearchIndex.Hit last = first.getLast();
      List<SearchIndex.Hit> second =
          index.search("coffee", 2, new SearchIndex.Cursor(last.createdAt(), last.tweetId()));

      assertThat(ids(first)).containsExactly("c", "b");
      assertThat(ids(second)).containsExactly("a");
    }

    @Test
    @DisplayName("a cursor past the end returns nothing")
    void exhausted() {
      List<SearchIndex.Hit> all = index.search("coffee", 10, null);
      SearchIndex.Hit last = all.getLast();

      assertThat(
              index.search("coffee", 10, new SearchIndex.Cursor(last.createdAt(), last.tweetId())))
          .isEmpty();
    }
  }
}
