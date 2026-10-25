-- Wizard state for the tap-driven UI.
--
-- V1 is frozen: it is applied on the deployed bot, so every schema change
-- starts here.
--
-- Everything else in the Telegram layer stays stateless. A button carries its
-- own meaning in callback_data, so a tap on a message from last week still does
-- the right thing. Typed input is the exception: when the bot asks what a
-- deadline is called, the answer is a plain text message with no button to hang
-- context on, so the step in progress has to live somewhere.
--
-- It lives here rather than in a map in memory because a deploy or a restart
-- would otherwise eat a half-finished deadline. A draft is one row per chat,
-- since you can only be inside one flow at a time, and starting another
-- overwrites it.

CREATE TABLE ui_draft (
    chat_id    BIGINT      PRIMARY KEY,
    -- Which question was asked last, e.g. NEW_NAME or QUIET_CUSTOM. See
    -- Step in UiDraftRepository.
    step       TEXT        NOT NULL,
    -- The deadline being edited, if any. NULL for a new one.
    target_id  BIGINT,
    -- Filled in as the flow goes; NULL means "not answered yet".
    name       TEXT,
    due_date   DATE,
    due_time   TIME,
    -- The message that asked for typed input, so the answer can rewrite that
    -- question instead of leaving a stale prompt above the next step.
    prompt_message_id BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Sweeping abandoned drafts is a range scan on age.
CREATE INDEX idx_ui_draft_updated ON ui_draft (updated_at);
