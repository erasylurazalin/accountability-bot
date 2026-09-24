#!/usr/bin/env bash
#
# Restore a dump into the running database. Destructive: it drops the existing
# schema first. Requires an explicit confirmation argument rather than a prompt,
# so it cannot be triggered by a stray Enter.
#
# The drop and the load run in one transaction, so a dump that fails halfway
# rolls back to the database as it was, not to an empty one.
#
# DB_CONTAINER points it at another Postgres container, which is how the drill
# restores into a scratch database. The bot is only stopped when restoring into
# the live one.
#
# Usage: scripts/restore.sh <dump.sql.gz> --yes-really
set -euo pipefail

DUMP="${1:-}"
CONFIRM="${2:-}"
DEPLOY_DIR="${DEPLOY_DIR:-/home/era/homelab/accountability-bot}"
LIVE_DB="accountability-db"
DB_CONTAINER="${DB_CONTAINER:-$LIVE_DB}"

[[ -f "$DUMP" ]] || { echo "usage: $0 <dump.sql.gz> --yes-really" >&2; exit 2; }
[[ "$CONFIRM" == "--yes-really" ]] || {
    echo "refusing to restore without --yes-really (this DROPs the current schema)" >&2
    exit 2
}
gzip -t "$DUMP" || { echo "$DUMP is not a valid gzip file" >&2; exit 1; }

# shellcheck disable=SC1091
set -a; source "$DEPLOY_DIR/.env"; set +a

if [[ "$DB_CONTAINER" == "$LIVE_DB" ]]; then
    echo "stopping the bot so it cannot write mid-restore"
    docker compose --project-directory "$DEPLOY_DIR" stop bot
    # Start it again however this ends. Without this, a failed restore would
    # exit here and leave the bot down.
    trap 'docker compose --project-directory "$DEPLOY_DIR" start bot' EXIT
fi

{
    echo "SET client_min_messages = warning;"
    echo "DROP SCHEMA public CASCADE;"
    echo "CREATE SCHEMA public;"
    gunzip -c "$DUMP"
} | docker exec -i "$DB_CONTAINER" \
    psql -q --single-transaction -v ON_ERROR_STOP=1 \
         -U "${POSTGRES_USER:-accountability}" -d "${POSTGRES_DB:-accountability}" >/dev/null

echo "restored $DB_CONTAINER from $DUMP"
