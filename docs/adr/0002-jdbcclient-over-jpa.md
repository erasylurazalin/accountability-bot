# ADR 0002: `JdbcClient` and Flyway, no JPA

Status: accepted
Date: 2026-08-19, updated 2026-09-26 for the deadline-only design

## Context

The stack is Java 21, Spring Boot 3, PostgreSQL 16 and Flyway, which does not
say how the application talks to the database. The default reflex in this
stack is Spring Data JPA.

The deployment target is a 2011 laptop with 4 GB of RAM, shared with AdGuard,
on a 5400 rpm disk. Memory is the binding constraint, and JVM startup off that
disk is already slow.

## Decision

Use `JdbcClient` with hand-written SQL. Flyway owns the schema. No Hibernate,
no Spring Data repositories.

## Consequences

**Lower footprint and faster startup.** Hibernate adds a large dependency tree,
entity scanning, and metamodel construction at boot, all of which cost heap and
seconds on a slow disk.

**The interesting SQL stays visible.** The correctness of the scheduler lives
in two statements in `ReminderRepository`:
`INSERT ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING id` persists a
sampled fire time exactly once, and
`UPDATE ... SET claimed_at = ... WHERE claimed_at IS NULL AND sent_at IS NULL`
claims a reminder, where the row count says whether this attempt won. Both do
real concurrency work. Through JPA they would be either invisible or fought
against.

**Constraints live in the database.** `ck_sent_implies_claimed` enforces claim
before send, and `ck_quiet_hours_both_or_neither` stops half-set quiet hours. They
hold in Postgres regardless of which code path writes the row.

**Cost:** mapping rows to records by hand, and no free dirty-checking or lazy
loading. For six tables with no object graph to speak of, that is a small price,
and this is not an application whose difficulty is persistence.

## Revisit when

The schema grows an aggregate with a real parent and child lifecycle, where
cascading saves and deletes would be written by hand over and over.
