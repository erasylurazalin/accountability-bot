#!/usr/bin/env bash
#
# Build the bot image on era-arch and ship it to era-server over Tailscale.
#
# Run this ON era-arch. The two machines are on different networks, joined only
# by Tailscale, so era-arch pushes and era-server never pulls from anywhere:
# not from era-arch, and not from Docker Hub, which fails often from home.
#
# Secrets come from the environment (~/.secrets on era-arch) and are rendered
# into the server's .env, mode 600. Nothing secret is stored in the repository
# or printed.
#
# Usage: scripts/deploy.sh [--no-build]
set -euo pipefail

DEPLOY_HOST="${DEPLOY_HOST:-era-server}"   # an SSH alias, resolved by ~/.ssh/config
DEPLOY_DIR="${DEPLOY_DIR:-/home/era/homelab/accountability-bot}"
IMAGE="${IMAGE:-accountability-bot}"
DB_IMAGE="postgres:16-alpine"              # must match compose.yaml

DO_BUILD=1
for arg in "$@"; do
    case "$arg" in
        --no-build) DO_BUILD=0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

: "${TELEGRAM_BOT_TOKEN:?not set, add it to ~/.secrets}"
: "${TELEGRAM_OWNER_CHAT_ID:?not set, add it to ~/.secrets}"
: "${POSTGRES_PASSWORD:?not set, add it to ~/.secrets}"

# The .env values are single-quoted, so a single quote inside one would break
# the file. openssl rand -hex never produces one.
for v in "$TELEGRAM_BOT_TOKEN" "$TELEGRAM_OWNER_CHAT_ID" "$POSTGRES_PASSWORD"; do
    [[ "$v" != *"'"* ]] || { echo "a secret contains a single quote, which .env cannot hold here" >&2; exit 1; }
done

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
remote() { ssh "$DEPLOY_HOST" "$@"; }

if [[ "$DO_BUILD" == 1 ]]; then
    TAG="$(date +%Y%m%d-%H%M%S)"
    log "building $IMAGE:$TAG"
    docker build -t "$IMAGE:$TAG" -t "$IMAGE:latest" "$SRC_DIR"
else
    TAG=latest
    docker image inspect "$IMAGE:$TAG" >/dev/null
fi

log "checking $DEPLOY_HOST is reachable"
remote true

# docker save ships every layer every time, about 100 MB compressed. Fine over
# Tailscale for a deploy that happens occasionally; a registry would only send
# the changed layers.
log "shipping $IMAGE:$TAG"
docker save "$IMAGE:$TAG" | zstd -T0 -3 -q | remote 'zstd -d -q | docker load'

if ! remote "docker image inspect $DB_IMAGE >/dev/null 2>&1"; then
    log "shipping $DB_IMAGE (first deploy only)"
    docker image inspect "$DB_IMAGE" >/dev/null 2>&1 || docker pull "$DB_IMAGE"
    docker save "$DB_IMAGE" | zstd -T0 -3 -q | remote 'zstd -d -q | docker load'
fi

log "writing compose.yaml, scripts and .env to $DEPLOY_HOST:$DEPLOY_DIR"
remote "mkdir -p '$DEPLOY_DIR/scripts'"
remote "cat > '$DEPLOY_DIR/compose.yaml'" < "$SRC_DIR/compose.yaml"
for s in backup.sh restore.sh; do
    remote "cat > '$DEPLOY_DIR/scripts/$s' && chmod +x '$DEPLOY_DIR/scripts/$s'" < "$SRC_DIR/scripts/$s"
done

# Postgres reads POSTGRES_PASSWORD only when it first creates its data
# directory. Changing it in ~/.secrets later breaks the bot's login until the
# database user is changed to match (ALTER USER), or the volume is dropped.
printf "%s\n" \
    "TELEGRAM_BOT_TOKEN='$TELEGRAM_BOT_TOKEN'" \
    "TELEGRAM_OWNER_CHAT_ID='$TELEGRAM_OWNER_CHAT_ID'" \
    "POSTGRES_PASSWORD='$POSTGRES_PASSWORD'" \
    "BOT_TESTMODE='${BOT_TESTMODE:-false}'" \
    "IMAGE_TAG='$TAG'" \
    | remote "umask 077 && cat > '$DEPLOY_DIR/.env'"

log "starting the stack"
remote "docker compose --project-directory '$DEPLOY_DIR' up -d"

log "waiting for health (the bot's start period is 90 s)"
for _ in $(seq 1 36); do
    state="$(remote "docker inspect -f '{{.State.Health.Status}}' accountability-bot 2>/dev/null" || echo starting)"
    if [[ "$state" == "healthy" ]]; then
        log "healthy, deployed $IMAGE:$TAG"
        exit 0
    fi
    sleep 5
done

echo "not healthy after 3 minutes. Look at:" >&2
echo "  ssh $DEPLOY_HOST docker compose --project-directory '$DEPLOY_DIR' logs bot" >&2
exit 1
