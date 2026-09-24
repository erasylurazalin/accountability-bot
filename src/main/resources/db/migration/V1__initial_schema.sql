-- One deadline, one row. That is the whole product: a thing due at a moment,
-- and reminders that arrive at unpredictable times and get more frequent as
-- that moment approaches.
--
-- There is no habit type, no per-day check-in, no streak and no excuse log.
-- Those existed in an earlier draft and were cut: a deadline is not a daily
-- thing, so a table giving it one row per day only ever restated the deadline.

CREATE TABLE deadline (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chat_id    BIGINT      NOT NULL,
    name       TEXT        NOT NULL CHECK (length(btrim(name)) > 0),
    due_at     TIMESTAMPTZ NOT NULL,
    -- DONE is not reachable from any command today: a finished deadline is
    -- removed. It is here so that adding /done later is not a migration.
    status     TEXT        NOT NULL DEFAULT 'PENDING'
               CHECK (status IN ('PENDING', 'DONE', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The scheduler's hot path: which deadlines are still live, soonest first.
CREATE INDEX idx_deadline_pending ON deadline (due_at) WHERE status = 'PENDING';
CREATE INDEX idx_deadline_chat ON deadline (chat_id, status);

-- Timezone and quiet hours belong to the person, not to a deadline.
--
-- Quiet hours are left NULL until chosen, and the scheduler sends nothing at
-- all while they are NULL. That is the fail-closed direction: guessing a waking
-- window is how a bot earns a permanent mute at 04:00.
CREATE TABLE user_settings (
    chat_id           BIGINT      PRIMARY KEY,
    timezone          TEXT        NOT NULL DEFAULT 'Asia/Almaty',
    quiet_hours_start TIME,
    quiet_hours_end   TIME,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Half-configured quiet hours are meaningless, so the database refuses them.
    CONSTRAINT ck_quiet_hours_both_or_neither
        CHECK ((quiet_hours_start IS NULL) = (quiet_hours_end IS NULL))
);

-- Live overrides for the sampler, changeable from Telegram with /lambda.
--
-- The constants in application.yml are a guess and the only way to settle them
-- is to live with the bot. Editing yaml and rebuilding an image to find that
-- out is enough friction that the experiment does not happen.
--
-- One row. NULL means "no override, use application.yml", which is what makes
-- /lambda reset a single UPDATE rather than a hunt for the original numbers.
CREATE TABLE reminder_tuning (
    id                     INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    lambda_base            DOUBLE PRECISION CHECK (lambda_base > 0),
    lambda_min             DOUBLE PRECISION CHECK (lambda_min > 0),
    lambda_max             DOUBLE PRECISION CHECK (lambda_max > 0),
    min_spacing_minutes    INT CHECK (min_spacing_minutes >= 0),
    max_per_deadline_daily INT CHECK (max_per_deadline_daily > 0),
    max_per_user_daily     INT CHECK (max_per_user_daily > 0),
    schedule_ahead_minutes INT CHECK (schedule_ahead_minutes > 0),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Only checkable when both are overridden; a mixed case inherits the other
    -- bound from yaml and is clamped in ReminderTuning instead.
    CONSTRAINT ck_lambda_range
        CHECK (lambda_min IS NULL OR lambda_max IS NULL OR lambda_max >= lambda_min)
);

INSERT INTO reminder_tuning (id) VALUES (1);

-- A reminder_event row IS the schedule. The fire time is sampled once and
-- persisted here before it is used, which is what makes a random schedule
-- survive a restart: after a crash the process reads a decision that was
-- already made, instead of drawing a different number and sending twice.
--
-- Four states, distinguished without a status column:
--
--   scheduled, not due   scheduled_for > now,  claimed_at NULL
--   due                  scheduled_for <= now, claimed_at NULL
--   in flight            claimed_at set,       sent_at NULL
--   sent                 sent_at set
--
-- The in-flight state is the crash window that claim-before-send deliberately
-- accepts: better a late reminder than a duplicate, because duplicates train
-- you to ignore the bot.
CREATE TABLE reminder_event (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deadline_id         BIGINT      NOT NULL REFERENCES deadline (id) ON DELETE CASCADE,
    -- d{deadline}:n{seq}. seq is a plain counter rather than anything derived
    -- from the clock, because a sampled time cannot be recomputed.
    idempotency_key     TEXT        NOT NULL,
    seq                 INT         NOT NULL,
    scheduled_for       TIMESTAMPTZ NOT NULL,
    claimed_at          TIMESTAMPTZ,
    sent_at             TIMESTAMPTZ,
    telegram_message_id BIGINT,
    body                TEXT,
    CONSTRAINT uq_reminder_idempotency UNIQUE (idempotency_key),
    -- Cannot be sent without having been claimed first: claim-before-send
    -- expressed as a constraint rather than as a convention.
    CONSTRAINT ck_sent_implies_claimed
        CHECK (sent_at IS NULL OR claimed_at IS NOT NULL)
);

-- Reminders waiting to fire.
CREATE INDEX idx_reminder_event_due ON reminder_event (scheduled_for)
    WHERE sent_at IS NULL AND claimed_at IS NULL;

-- Claims interrupted between the claim and the Telegram call.
CREATE INDEX idx_reminder_event_inflight ON reminder_event (claimed_at)
    WHERE sent_at IS NULL AND claimed_at IS NOT NULL;

-- Counting against the daily caps.
CREATE INDEX idx_reminder_event_deadline ON reminder_event (deadline_id, scheduled_for);

CREATE TABLE audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor       TEXT        NOT NULL,
    action      TEXT        NOT NULL,
    entity      TEXT,
    entity_id   BIGINT,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail_json JSONB
);

CREATE INDEX idx_audit_log_at ON audit_log (at DESC);

-- Telegram long-poll offset. Single row; survives restarts so updates are
-- neither replayed nor lost.
CREATE TABLE poll_state (
    id             INT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_update_id BIGINT NOT NULL DEFAULT 0
);

INSERT INTO poll_state (id, last_update_id) VALUES (1, 0);
