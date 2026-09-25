# ADR 0001: a single tick job, not per-reminder timers

Status: accepted
Date: 2026-08-19, updated 2026-09-26 for the deadline-only design

## Context

The scheduler could be Spring `@Scheduled`, Quartz, or a single tick job that
recomputes due work. Restart safety decides it: the app restarting at 07:00:01
must not send a 07:00 reminder twice, and must not silently drop it either.

era-server makes this concrete rather than theoretical. A cold JVM start off a
5400 rpm laptop drive takes 20 to 40 seconds, and the box also runs AdGuard for
the home network, so it gets restarted for reasons that have nothing to do with
the bot. Restarts are a normal event here, not an edge case.

Reminder times are random (a non-homogeneous Poisson process), which rules out
the usual trick of deriving a reminder's identity from the clock. A second draw
after a restart is a different number.

## Decision

One `@Scheduled` tick, every 60 seconds, that works only from the database:

1. Expire deadlines that lapsed, and cancel anything queued for them.
2. Plan: extend each deadline's chain of random fire times up to three hours
   ahead, writing every draw to `reminder_event` before it is used.
3. Deliver planned reminders whose time has come.
4. Recover reminder claims interrupted mid-send.

No in-memory timer holds state that matters. Quartz was rejected: its clustering
and persistent job store solve a coordination problem this deployment does not
have (one instance, one user), at the cost of extra tables, extra threads, and
extra memory on a box where memory is the binding constraint.

## Consequences

**A fire time is persisted before it is used.** Each draw gets the key
`d{deadline}:n{seq}`, unique in `reminder_event`. After a restart the tick finds
the plans already written and delivers them, instead of drawing new ones.
Every draw is kept, including one that lands past the look-ahead window or past
the deadline. Throwing those away and drawing again next tick gave the sampler a
fresh chance every minute, which pinned a deadline a month out at the daily cap:
6 reminders a day instead of about 0.5. `ReminderSchedulerTest` guards this.

**Claim before send, not after.** The row is claimed (`claimed_at` and the
message body in one `UPDATE`), then Telegram is called, then `sent_at` is filled
in. A crash in between leaves a claim with a null `sent_at`, which the reaper
retries after two minutes and abandons after an hour. The other ordering (send,
then record) can double-send, which for a reminder bot is the worse failure: a
reminder that arrives twice trains you to ignore it. `ck_sent_implies_claimed`
enforces the order in the schema.

**Limits apply to the person, not the deadline.** The daily cap is checked when
planning, the 45-minute spacing when sending: a reminder too close to the last
one waits rather than being redrawn, so the rate stays honest.

**Latency is bounded by the tick interval.** A reminder can be up to 60 seconds
late. For this product that is invisible.

## Revisit when

There is ever more than one instance of the bot. Two ticks racing are already
safe (the unique key and the conditional claim decide the winner), but
coordinating work across machines is the problem Quartz actually solves.
