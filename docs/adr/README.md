# Architecture Decision Records

Each record states the context, the decision, the alternatives that were rejected, and
the consequences — including the bad ones. A decision without stated costs is marketing,
not engineering.

Format: a trimmed [MADR](https://adr.github.io/madr/). Records are immutable once
accepted; a change means a new record that supersedes the old one.

| # | Title | Status |
|---|-------|--------|
| [0001](0001-service-decomposition.md) | Four services, one worker, one frontend | Accepted |
| [0002](0002-rest-over-grpc.md) | REST/JSON for synchronous inter-service calls | Accepted |
| [0003](0003-event-transport.md) | One `EventPublisher` interface, two adapters | Accepted |
| [0004](0004-shared-database-schema-per-service.md) | Shared PostgreSQL, schema per service | Accepted |
| [0005](0005-uuidv7-identifiers.md) | UUIDv7 primary keys | Accepted |
| [0006](0006-hybrid-timeline-fanout.md) | Hybrid timeline fan-out | Accepted |
| [0007](0007-postgres-fts-over-opensearch.md) | PostgreSQL full-text search, not OpenSearch | Accepted |
| [0008](0008-expand-contract-migrations.md) | Expand–contract database migrations | Accepted |
| [0009](0009-three-tier-environment-model.md) | Three-tier environment model | Accepted |
| [0010](0010-jlink-distroless-base-image.md) | jlink runtime on distroless base images | Accepted |
