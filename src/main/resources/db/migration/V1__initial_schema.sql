-- Phase 1 schema. Derived from NOTES.md section 8, with the stake table
-- deliberately deferred to a later migration (NOTES.md section 3 puts
-- partner-notification after streaks + logged excuses).

CREATE TABLE commitment (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    chat_id             BIGINT      NOT NULL,
    name                TEXT        NOT NULL,
    type                TEXT        NOT NULL CHECK (type IN ('HABIT', 'DEADLINE')),
    active              BOOLEAN     NOT NULL DEFAULT TRUE,
    timezone            TEXT        NOT NULL DEFAULT 'Asia/Almaty',
    -- Daily due window, in the commitment's own local time.
    window_start        TIME        NOT NULL DEFAULT '06:00',
    window_end          TIME        NOT NULL DEFAULT '22:00',
    -- Quiet hours suppress reminders; they do not change the due window.
    quiet_hours_start   TIME        NOT NULL DEFAULT '23:00',
    quiet_hours_end     TIME        NOT NULL DEFAULT '08:00',
    -- Phase 1 reminder cadence. Phase 2 replaces this with the Poisson
    -- sampler described in NOTES.md section 4.
    reminder_interval_minutes INT   NOT NULL DEFAULT 240,
    max_reminders_per_day     INT   NOT NULL DEFAULT 4,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_commitment_active ON commitment (active) WHERE active;

-- Only meaningful for type = 'DEADLINE'. Unused in phase 1; present so the
-- Poisson work in phase 2 does not need a schema change to the core table.
CREATE TABLE deadline_detail (
    commitment_id   BIGINT      PRIMARY KEY REFERENCES commitment (id) ON DELETE CASCADE,
    due_at          TIMESTAMPTZ NOT NULL,
    lambda_base     DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    lambda_min      DOUBLE PRECISION NOT NULL DEFAULT 0.2,
    lambda_max      DOUBLE PRECISION NOT NULL DEFAULT 10.0
);

-- One row per commitment per due window. The unique constraint is what makes
-- window materialisation idempotent across restarts.
CREATE TABLE check_in (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commitment_id     BIGINT      NOT NULL REFERENCES commitment (id) ON DELETE CASCADE,
    local_date        DATE        NOT NULL,
    due_window_start  TIMESTAMPTZ NOT NULL,
    due_window_end    TIMESTAMPTZ NOT NULL,
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

CREATE INDEX idx_check_in_pending ON check_in (status, due_window_end)
    WHERE status = 'PENDING';

CREATE TABLE streak (
    commitment_id   BIGINT  PRIMARY KEY REFERENCES commitment (id) ON DELETE CASCADE,
    current_len     INT     NOT NULL DEFAULT 0,
    best_len        INT     NOT NULL DEFAULT 0,
    last_success_on DATE
);

-- Filled by the nightly batch (NOTES.md section 5). Empty in phase 1; the
-- static template provider is used until a generator is wired up.
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

-- idempotency_key is the defence against restart double-fire (NOTES.md section 7).
-- A row is INSERTed to claim the send before the Telegram call is made; sent_at
-- is filled in afterwards. A claim with a NULL sent_at is a crash-in-flight and
-- is retried by the reaper within a grace window.
CREATE TABLE reminder_event (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commitment_id       BIGINT      NOT NULL REFERENCES commitment (id) ON DELETE CASCADE,
    check_in_id         BIGINT      REFERENCES check_in (id) ON DELETE CASCADE,
    reminder_text_id    BIGINT      REFERENCES reminder_text (id) ON DELETE SET NULL,
    idempotency_key     TEXT        NOT NULL,
    scheduled_for       TIMESTAMPTZ NOT NULL,
    claimed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at             TIMESTAMPTZ,
    telegram_message_id BIGINT,
    body                TEXT,
    CONSTRAINT uq_reminder_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_reminder_event_unsent ON reminder_event (claimed_at)
    WHERE sent_at IS NULL;

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
