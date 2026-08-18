#!/usr/bin/env bash
#
# Restore a dump into the running database. Destructive: it drops the existing
# schema first. Requires an explicit confirmation argument rather than a prompt,
# so it cannot be triggered by a stray Enter.
#
# Usage: scripts/restore.sh <dump.sql.gz> --yes-really
set -euo pipefail

DUMP="${1:-}"
CONFIRM="${2:-}"
DEPLOY_DIR="${DEPLOY_DIR:-/home/era/homelab/accountability-bot}"

[[ -f "$DUMP" ]] || { echo "usage: $0 <dump.sql.gz> --yes-really" >&2; exit 2; }
[[ "$CONFIRM" == "--yes-really" ]] || {
    echo "refusing to restore without --yes-really (this DROPs the current schema)" >&2
    exit 2
}

# shellcheck disable=SC1091
set -a; source "$DEPLOY_DIR/.env"; set +a

echo "stopping the bot so it cannot write mid-restore"
docker compose --project-directory "$DEPLOY_DIR" stop bot

gunzip -c "$DUMP" | docker exec -i accountability-db \
    psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-accountability}" -d "${POSTGRES_DB:-accountability}"

docker compose --project-directory "$DEPLOY_DIR" start bot
echo "restored from $DUMP"
