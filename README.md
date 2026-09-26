# accountability-bot

My personal Telegram bot. I tell it something is due at a certain time, and it
reminds me at random moments that get more frequent as the deadline gets
closer.

The randomness is the point. Reminders on a fixed schedule turn into a calendar
app, and I stop seeing them within a week.

It's built for me, not packaged for anyone else to run.

Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, Docker Compose.

## Commands

```
/new tomorrow 23:59 hand in essay     add a deadline (today, tomorrow or YYYY-MM-DD)
/list                                 pending, plus anything that lapsed this week
/edit 1 2026-09-02 09:00              move it (reopens a lapsed one)
/edit 1 a better name                 rename it
/remove 1                             delete it
/quiet 02:00 10:00                    hours to stay silent
/quiet off                            clear them
```

No reminders go out until quiet hours are set, so a wrong guess can't ping me
at 4 am. Only my own chat id can use the bot; everything else is dropped.

## How reminders are timed

Fire times come from a non-homogeneous Poisson process: the rate grows as the
deadline gets closer, with a daily cap and a minimum gap between reminders. The
knobs are in `application.yml` under `bot.reminder`.

Random times can't be recomputed after a restart, so each one is written to
Postgres before it's used, and a one-minute tick sends whatever is due. A
reminder is marked as claimed before it goes to Telegram, so two ticks can
never both send it. A crash mid-send is retried, which can occasionally mean a
duplicate: Telegram has no way to ask whether a message already arrived.

Why a tick and not Quartz: [ADR 0001](docs/adr/0001-tick-based-scheduler.md).
Why JdbcClient and not JPA: [ADR 0002](docs/adr/0002-jdbcclient-over-jpa.md).

## Where it runs

On an old low-memory laptop at home, behind NAT. That shaped the setup:

- **Long polling, no inbound ports.** The bot connects out to Telegram.
- **No builds on the server.** The image is built on another machine and
  shipped over SSH (`docker save | zstd | ssh | docker load`, no registry).
- **Memory limits on every container:** 512M for the bot, 256M for Postgres.
  The JVM runs SerialGC, which suits two cores and a small heap.

The Dockerfile is multi-stage: Gradle build, then Spring Boot's layered jar
split into layers, then a JRE-only runtime as a non-root user.

## Backups and monitoring

Two systemd timers on the server:

- **Nightly backup.** `pg_dump`, checked with `gzip -t` before anything old is
  pruned. `scripts/restore.sh` drops and reloads in one transaction, and the
  restore has been tested against a scratch database.
- **Health check every 5 minutes.** It messages me on Telegram when the bot is
  down or the last backup is too old, once when it breaks and once when it's
  fixed. The bot can't report its own death, so this runs outside it.

Backups stay on the same machine for now. An off-machine copy is next.

## Tests and CI

GitHub Actions runs the tests, builds the image and scans it with Trivy on every
push. The build fails on high or critical CVEs that have a fix available.
Dependabot keeps the dependencies current.

The scheduler tests run the real tick against a real Postgres (Testcontainers),
with an injected clock so days of ticks take about a second.

Not covered yet: command parsing, DST, expiry.
