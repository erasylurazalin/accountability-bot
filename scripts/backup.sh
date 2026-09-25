#!/usr/bin/env bash
#
# pg_dump the bot database. A backup nobody has restored is not a backup, so
# scripts/restore.sh exists next to this one and the restore drill has been run.
# Runs nightly from scripts/systemd/accountability-backup.timer.
set -euo pipefail

# Dumps hold deadline names. Readable by me only.
umask 077

DEPLOY_DIR="${DEPLOY_DIR:-/home/era/homelab/accountability-bot}"
BACKUP_DIR="${BACKUP_DIR:-/home/era/backups/accountability}"
KEEP_DAYS="${KEEP_DAYS:-30}"

# shellcheck disable=SC1091
set -a; source "$DEPLOY_DIR/.env"; set +a

mkdir -p "$BACKUP_DIR"
OUT="$BACKUP_DIR/accountability-$(date +%Y%m%d-%H%M%S).sql.gz"

docker exec accountability-db \
    pg_dump -U "${POSTGRES_USER:-accountability}" -d "${POSTGRES_DB:-accountability}" \
    | gzip -9 > "$OUT"

# A zero-length or truncated dump is worse than no dump, because it looks fine
# in a listing. Verify before pruning anything.
if ! gzip -t "$OUT" 2>/dev/null || [[ ! -s "$OUT" ]]; then
    echo "dump failed verification: $OUT" >&2
    rm -f "$OUT"
    exit 1
fi

echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
find "$BACKUP_DIR" -name 'accountability-*.sql.gz' -mtime "+$KEEP_DAYS" -delete
