# ADR 0001 — A single tick job, not per-commitment timers

Status: accepted (phase 1)
Date: 2026-08-19

## Context

`NOTES.md` §9 left the scheduler undecided between Spring `@Scheduled`, Quartz,
and "a single tick job that recomputes due work", and named restart-safety as
the deciding factor. §7 sharpens why: the app restarting at 07:00:01 must not
send a 07:00 reminder twice, and must not silently drop it either.

era-server makes this concrete rather than theoretical. A cold JVM start off a
5400 rpm laptop drive takes roughly 20–40 seconds, and the box hosts household
DNS, so it gets restarted for reasons that have nothing to do with the bot.
Restarts are a normal event here, not an edge case.

## Decision

One `@Scheduled` tick, default every 60 seconds, that recomputes everything due
from the database:

1. Materialise today's (and recent) check-in windows.
2. Expire windows that closed while still `PENDING`.
3. Send reminders whose slot is due.
4. Recover reminder claims interrupted mid-send.

No in-memory timer holds state that matters. Quartz was rejected: its clustering
and persistent job store solve a coordination problem this deployment does not
have (one instance, one user), at the cost of extra tables, extra threads, and
extra memory on a box where memory is the binding constraint.

## Consequences

**Idempotency comes from the database, not from timing.** Each potential send
derives a key of `c{id}:{localDate}:s{slot}`, where `slot` is
`minutes_since_window_start / reminder_interval`. That is a pure function of the
clock, so restarting mid-interval recomputes the identical key and the unique
constraint on `reminder_event.idempotency_key` rejects the duplicate.

**Claim before send, not after.** The row is inserted to claim the slot, then
Telegram is called, then `sent_at` is filled in. A crash in between leaves a
claim with a null `sent_at`, which the reaper retries after two minutes and
abandons after an hour. The alternative ordering — send, then record — can
double-send, which for a reminder bot is the worse failure: a reminder that
arrives twice trains you to ignore it.

**Latency is bounded by the tick interval.** A reminder can be up to 60 seconds
late. For this product that is invisible.

**A missed window becomes a recorded miss.** Backfilling up to `backfill-days`
means an outage produces `EXPIRED` rows rather than a silent gap in the history,
which matters because the adherence history is the actual product. The backfill
is bounded so a week-long outage cannot manufacture a week of fake misses.

## Revisit when

Phase 2 replaces the fixed interval with the non-homogeneous Poisson sampler
(`NOTES.md` §4). The sampled fire time has to be persisted for the same key to
survive a restart, so the slot derivation changes — but the claim-then-send
ordering and the unique constraint do not.
