-- Initial schema. Derived from NOTES.md section 8, with the stake table
-- deliberately deferred to a later migration (NOTES.md section 3 puts
-- partner-notification after streaks + logged excuses).
--
-- Reminder timing follows NOTES.md section 4: fire times are sampled, not
-- scheduled, so nothing here encodes an interval or a fixed cadence. A daily
-- commitment is due by the end of its local day; there is no configurable
-- window, because a window that cannot be set from Telegram is complexity
-- without a feature.

CREATE TABLE commitment (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chat_id             BIGINT      NOT NULL,
    name                TEXT        NOT NULL,
    type                TEXT        NOT NULL CHECK (type IN ('HABIT', 'DEADLINE')),
    active              BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_commitment_active ON commitment (active) WHERE active;

-- Timezone and quiet hours are properties of the person, not of the task, so
-- they live here rather than on commitment. A person is in one place at a time;
-- a per-commitment timezone would let "gym" and "coursework" disagree about
-- what day it is.
--
-- One row is created for a chat the first time it creates a commitment, so the
-- scheduler always has a timezone to work in. Quiet hours are left NULL by that
-- insert: NULL means "not chosen yet", and the scheduler refuses to send
-- anything at all in that state rather than inventing a waking window.
-- Guessing here is how you get pinged at 04:00.
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

-- Only meaningful for type = 'DEADLINE'. A habit's deadline is the end of its
-- local day and needs no row here.
CREATE TABLE deadline_detail (
    commitment_id   BIGINT      PRIMARY KEY REFERENCES commitment (id) ON DELETE CASCADE,
    due_at          TIMESTAMPTZ NOT NULL
);

-- One row per commitment per local day. The unique constraint is what makes
-- materialisation idempotent across restarts.
--
-- due_at is the exclusive upper bound: midnight at the start of the following
-- local day. "Due by 23:59" and "due before midnight" are the same deadline,
-- and an exclusive bound avoids a dead second at 23:59:59.
CREATE TABLE check_in (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commitment_id     BIGINT      NOT NULL REFERENCES commitment (id) ON DELETE CASCADE,
    local_date        DATE        NOT NULL,
    due_at            TIMESTAMPTZ NOT NULL,
    status            TEXT        NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING', 'DONE', 'SKIPPED', 'EXPIRED')),
    completed_at      TIMESTAMPTZ,
    excuse_text       TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_check_in_window UNIQUE (commitment_id, local_date),
    -- NOTES.md section 8: an excuse is mandatory on SKIPPED. Enforced in the
    -- database, not just the handler, so the friction cannot be bypassed.
    CONSTRAINT ck_skip_requires_excuse
        CHECK (status <> 'SKIPPED' OR (excuse_text IS NOT NULL AND length(btrim(excuse_text)) > 0))
);

CREATE INDEX idx_check_in_pending ON check_in (status, due_at)
    WHERE status = 'PENDING';

CREATE TABLE streak (
    commitment_id   BIGINT  PRIMARY KEY REFERENCES commitment (id) ON DELETE CASCADE,
    current_len     INT     NOT NULL DEFAULT 0,
    best_len        INT     NOT NULL DEFAULT 0,
    last_success_on DATE
);

-- Filled by the nightly batch (NOTES.md section 5). Empty until a generator is
-- wired up; the static template provider is used until then.
CREATE TABLE reminder_text (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commitment_id BIGINT      REFERENCES commitment (id) ON DELETE CASCADE,
    urgency_tier  INT         NOT NULL,
    body          TEXT        NOT NULL CHECK (length(btrim(body)) > 0),
    generated_by  TEXT        NOT NULL,
    generated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at   TIMESTAMPTZ
);

CREATE INDEX idx_reminder_text_unconsumed
    ON reminder_text (commitment_id, urgency_tier)
    WHERE consumed_at IS NULL;

-- A reminder_event row IS the schedule. The fire time is sampled once and
-- persisted here before it is used, which is what makes a sampled schedule
-- survive a restart: after a crash the process reads a decision that was
-- already made instead of drawing a different random number and sending twice.
--
-- Four states, distinguished without a status column:
--
--   scheduled, not due   scheduled_for > now,  claimed_at NULL
--   due                  scheduled_for <= now, claimed_at NULL
--   in flight            claimed_at set,       sent_at NULL
--   sent                 sent_at set
--
-- The in-flight state is the crash window that claim-before-send deliberately
-- accepts: better a late reminder than a duplicate one, because duplicates
-- train you to ignore the bot.
CREATE TABLE reminder_event (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commitment_id       BIGINT      NOT NULL REFERENCES commitment (id) ON DELETE CASCADE,
    check_in_id         BIGINT      REFERENCES check_in (id) ON DELETE CASCADE,
    reminder_text_id    BIGINT      REFERENCES reminder_text (id) ON DELETE SET NULL,
    -- c{commitment}:{local_date}:n{seq}. seq is a per-day counter rather than a
    -- slot derived from the clock, because a sampled time cannot be recomputed.
    idempotency_key     TEXT        NOT NULL,
    seq                 INT         NOT NULL,
    scheduled_for       TIMESTAMPTZ NOT NULL,
    claimed_at          TIMESTAMPTZ,
    sent_at             TIMESTAMPTZ,
    telegram_message_id BIGINT,
    body                TEXT,
    CONSTRAINT uq_reminder_idempotency UNIQUE (idempotency_key),
    -- Cannot be sent without having been claimed first. This is invariant 1
    -- from CLAUDE.md expressed as a constraint rather than as a convention.
    CONSTRAINT ck_sent_implies_claimed
        CHECK (sent_at IS NULL OR claimed_at IS NOT NULL)
);

-- Reminders waiting to fire.
CREATE INDEX idx_reminder_event_due ON reminder_event (scheduled_for)
    WHERE sent_at IS NULL AND claimed_at IS NULL;

-- Claims interrupted between the claim and the Telegram call.
CREATE INDEX idx_reminder_event_inflight ON reminder_event (claimed_at)
    WHERE sent_at IS NULL AND claimed_at IS NOT NULL;

-- Used for the per-user daily cap in NOTES.md section 4.2: the cap that matters
-- is on the person, not on each task independently.
CREATE INDEX idx_reminder_event_sent ON reminder_event (commitment_id, sent_at)
    WHERE sent_at IS NOT NULL;

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
