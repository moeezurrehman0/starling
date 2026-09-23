-- The search index. Derived data only: every row here can be rebuilt from the tweets table
-- by replaying the stream from its trim horizon, and nothing reads this schema to answer a
-- question of record. That is what makes it safe for the index to be eventually consistent
-- and for a failed index write to be a retry rather than an incident.
CREATE SCHEMA IF NOT EXISTS search;

CREATE TABLE IF NOT EXISTS search.tweet (
    -- The DynamoDB tweet id, and therefore the join key back to the source of truth. text
    -- rather than uuid because the ids are UUIDv7 rendered as strings everywhere else in the
    -- system, and a type that round-trips differently in one store is a bug waiting for a
    -- string comparison.
    tweet_id   text        PRIMARY KEY,
    author_id  text        NOT NULL,
    body       text        NOT NULL,
    created_at timestamptz NOT NULL,
    -- Generated and stored, not computed per query and not maintained by a trigger. A
    -- generated column cannot drift from the text it indexes, which a trigger can the first
    -- time someone writes an UPDATE that forgets to fire it.
    --
    -- 'english' is a deliberate simplification, recorded as a limitation rather than
    -- presented as a feature: it stems and strips stop words for one language, so a corpus of
    -- tweets in several languages is indexed badly for all but one of them. Doing this
    -- properly means a language column and a per-row regconfig, which is a real feature and
    -- not a migration.
    document   tsvector    GENERATED ALWAYS AS (to_tsvector('english', body)) STORED
);

-- GIN, not GiST. GIN is slower to update and larger on disk; it is several times faster to
-- search, and this table is written once per tweet and read on every query.
CREATE INDEX IF NOT EXISTS tweet_document_idx ON search.tweet USING GIN (document);

-- Results are ordered newest first and paged by keyset, so the ordering columns need an index
-- of their own: without it every query that matches a common term sorts the whole match set.
CREATE INDEX IF NOT EXISTS tweet_recency_idx ON search.tweet (created_at DESC, tweet_id DESC);

-- Author-scoped search and, more importantly, the delete path when an account is removed.
CREATE INDEX IF NOT EXISTS tweet_author_idx ON search.tweet (author_id);
