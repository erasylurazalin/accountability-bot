# ADR 0002 — `JdbcClient` and Flyway, no JPA

Status: accepted (phase 1)
Date: 2026-08-19

## Context

`NOTES.md` §6 fixes Java 21 + Spring Boot 3 + PostgreSQL 16 + Flyway, but does
not say how the application talks to the database. The default reflex in this
stack is Spring Data JPA.

The deployment target is 4 GB of RAM total, shared with household DNS, on a
5400 rpm disk. §0.3 makes memory the binding constraint, and JVM startup off
that disk is already slow.

## Decision

Use `JdbcClient` with hand-written SQL. Flyway owns the schema, as already
decided. No Hibernate, no Spring Data repositories.

## Consequences

**Lower footprint and faster startup.** Hibernate adds a large dependency tree,
entity scanning, and metamodel construction at boot — all of which cost heap and
seconds on a slow disk.

**The interesting SQL stays visible.** The correctness of this system lives in
three statements: `INSERT ... ON CONFLICT DO NOTHING` for idempotent window
materialisation, `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING id`
for claiming a reminder slot, and `UPDATE ... FOR UPDATE SKIP LOCKED` for
consuming a pre-generated line. Each is doing real concurrency work. Expressed
through JPA these would be either invisible or fought against.

**Constraints live in the database.** `ck_skip_requires_excuse` enforces the
friction mechanism from §3 in Postgres rather than in a validator, so it holds
regardless of which code path writes the row.

**Cost:** mapping rows to records by hand, and no free dirty-checking or lazy
loading. For a schema of eight tables with no object graph to speak of, that is
a small price — and this is not an application whose difficulty is persistence.

## Revisit when

The schema grows an aggregate with genuine parent/child lifecycle management —
the `stake` table with per-kind config is the most likely candidate.
