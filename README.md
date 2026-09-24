# accountability-bot

My personal Telegram bot. I tell it something is due at a certain time, and it
reminds me at random moments that get more frequent as the deadline gets
closer.

It's built for me and my two machines, not packaged for anyone else to run.
There's no setup guide, and the deploy script assumes my network.

The randomness is the point. Reminders on a fixed schedule turn into a calendar
app, and I stop seeing them within a week.

Java 21, Spring Boot 3.4, PostgreSQL 16, Flyway, Docker Compose.

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

There are no reminders until quiet hours are set. Guessing them wrong means a
ping at 4 am, so the bot stays silent until I choose.

Only my own chat id can use it. The bot's username is public, so every other
chat is dropped before it reaches the command handling.

## How reminders are timed

Fire times come from a non-homogeneous Poisson process. The rate is
`lambda = base / days_remaining`, clamped between `lambda-min` and
`lambda-max`, with a per-person daily cap and a minimum gap between reminders.
All of it is in `application.yml` under `bot.reminder`.

Since the times are random, they can't be recomputed after a restart. So each
one is written to Postgres before it's used, and a one-minute tick delivers
whatever is due. A reminder is marked as claimed before it's sent to Telegram,
so a crash can make one go missing but can't make it arrive twice. Why that
beats Quartz: [ADR 0001](docs/adr/0001-tick-based-scheduler.md). Why JdbcClient
and no JPA: [ADR 0002](docs/adr/0002-jdbcclient-over-jpa.md).

## Where it runs

Two machines:

- **era-server** runs it. A 2011 laptop at home: 2 cores, 4 GB RAM, 5400 rpm
  disk, behind double NAT. It also runs AdGuard for the home network.
- **era-arch** builds it. That's my main PC, which lives with me in a dorm.

They're on different networks. The only link between them is Tailscale.

That split shapes the setup:

- **Long polling, no inbound ports.** The bot connects out to Telegram, so
  there's no port forwarding or dynamic DNS. The actuator port is bound to
  127.0.0.1.
- **No builds on the server.** A Gradle build wants 1 to 2 GB, which this laptop
  doesn't have to spare. The image is built on era-arch, and only the finished
  image goes over (`docker save | zstd | ssh | docker load`, no registry).
  era-server has no JDK.
- **Memory limits on every container**: 512M for the bot, 256M for Postgres.
  The JVM uses SerialGC, since G1's extra threads buy nothing on two cores with
  a small heap.
- **Postgres tuned for a slow disk**: small `shared_buffers`, a high
  `random_page_cost`, `synchronous_commit=off`.

The Dockerfile is multi-stage: a Gradle build, a stage that splits Spring
Boot's layered jar, and a JRE-only runtime running as a non-root user.
Dependencies sit in their own layer, which keeps rebuilds fast. It would also
keep deploys small with a registry, but `docker save` ships every layer, so each
deploy moves about 100 MB compressed.

`scripts/deploy.sh` runs on era-arch. It builds the image and streams it to
era-server over Tailscale. It also ships `postgres:16-alpine` the first time,
because pulling from Docker Hub fails a lot on my home connection. Then it
writes `compose.yaml` and the `.env`, runs `docker compose up -d`, and waits for
the health check. Secrets come from my shell environment and are written
straight into the server's `.env` (mode 600). They never touch the repo.

CI runs the tests and builds the image on every push to `master`. The
scheduler tests run the real tick against a real Postgres (Testcontainers),
with an injected clock so days of ticks take about a second.

## Operations

On era-server, where deploy puts everything in `~/homelab/accountability-bot`:

```bash
cd ~/homelab/accountability-bot
docker compose logs -f bot
curl -s localhost:8081/actuator/health

scripts/backup.sh                                # pg_dump, gzip -t on the result
scripts/restore.sh <dump.sql.gz> --yes-really    # drop and reload in one transaction
```

## Not done yet

- Tests cover quiet hours and the scheduler, not the command parsing yet.
- `lambda-base` and `lambda-max` are guesses. I'll tune them after living with
  the bot for a while.

## Layout

```
src/main/java/dev/era/accountability/
  config/     bot properties, sampler tuning, HTTP client
  domain/     Deadline, UserSettings
  repo/       JdbcClient repositories, including the claim and send SQL
  service/    the sampler, the tick, reminder wording
  telegram/   long-poll loop, command routing, API client
src/main/resources/db/migration/   Flyway
docs/adr/                          why the scheduler and persistence look the way they do
scripts/                           deploy, backup, restore
```
