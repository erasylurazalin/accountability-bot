#!/usr/bin/env bash
#
# Tell me on Telegram when the bot is down or the backups stopped. The bot
# cannot report its own death, so this runs outside it, every 5 minutes from
# scripts/systemd/accountability-health.timer.
#
# It messages only when the set of problems changes: once when something
# breaks, once when it is fixed. Sending uses the bot's token but only calls
# sendMessage, which does not compete with the bot's getUpdates loop.
set -euo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/home/era/homelab/accountability-bot}"
BACKUP_DIR="${BACKUP_DIR:-/home/era/backups/accountability}"
STATE_DIR="${STATE_DIR:-/home/era/.local/state/accountability-health}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8081/actuator/health}"
# Two checks in a row, so a deploy's 90-second restart does not page me.
FAILS_BEFORE_ALERT=2
# The backup runs nightly; 26 hours leaves slack for a late boot.
BACKUP_MAX_AGE_MIN=$((26 * 60))

# shellcheck disable=SC1091
set -a; source "$DEPLOY_DIR/.env"; set +a

mkdir -p "$STATE_DIR"
fails="$(cat "$STATE_DIR/fails" 2>/dev/null || echo 0)"
reported="$(cat "$STATE_DIR/reported" 2>/dev/null || true)"

# Compared between runs by name only, so details like the container state can
# change without sending another message.
problems=()
details=()

# The health endpoint includes the database check, so this also catches
# Postgres being down or refusing the bot's login.
body="$(curl -fsS --max-time 10 "$HEALTH_URL" 2>/dev/null || true)"
if [[ "$body" == *'"status":"UP"'* ]]; then
    fails=0
else
    fails=$((fails + 1))
    if (( fails >= FAILS_BEFORE_ALERT )); then
        state="$(docker inspect -f '{{.State.Status}}, exit code {{.State.ExitCode}}' accountability-bot 2>/dev/null \
                 || echo 'container not found')"
        problems+=(bot)
        details+=("The bot is unhealthy ($state).")
    fi
fi
echo "$fails" > "$STATE_DIR/fails"

if [[ -z "$(find "$BACKUP_DIR" -name 'accountability-*.sql.gz' -mmin "-$BACKUP_MAX_AGE_MIN" -print -quit 2>/dev/null)" ]]; then
    problems+=(backup)
    details+=("No backup in the last 26 hours.")
fi

current="${problems[*]}"
[[ "$current" == "$reported" ]] && exit 0

if [[ -n "$current" ]]; then
    text="era-server: ${details[*]}"
else
    text="era-server: all clear again."
fi

# The token goes to curl on stdin, not argv, so it never shows up in ps.
if printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "$TELEGRAM_BOT_TOKEN" \
    | curl -fsS --max-time 20 -K - \
        --data-urlencode "chat_id=$TELEGRAM_OWNER_CHAT_ID" \
        --data-urlencode "text=$text" >/dev/null; then
    printf '%s' "$current" > "$STATE_DIR/reported"
    echo "sent: $text"
else
    # Not recorded as reported, so the next run tries again.
    echo "could not reach Telegram, will retry: $text" >&2
    exit 1
fi
