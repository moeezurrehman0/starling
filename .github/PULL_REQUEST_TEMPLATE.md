## What and why

<!-- What changes, and what problem it solves. Link the issue if there is one. -->

## Tier

<!-- Which environment tiers does this affect? Delete those that do not apply. -->

- [ ] **L** — local (compose / kind)
- [ ] **S** — sandbox (KodeKloud EKS)
- [ ] **P** — production (written, never applied)
- [ ] none — docs or tooling only

## Checklist

- [ ] `./gradlew build` passes locally
- [ ] Tests cover the change; the coverage gate is met without being weakened
- [ ] An ADR is added or updated if this is an architectural decision
- [ ] `docs/16-gap-register.md` is updated if this makes the tiers diverge
- [ ] Migrations are expand–contract and backward compatible with the previous release
- [ ] No secrets, credentials or account identifiers are committed
- [ ] The PR title follows Conventional Commits

## Rollback

<!--
How is this undone if it misbehaves in production? "Revert the promotion PR" is a
perfectly good answer — but say so, and note anything that revert would not undo,
especially a migration.
-->
