#!/usr/bin/env bash
#
# Build the bot image on era-arch and load it onto era-server.
#
# Run this ON era-server. Nothing here compiles locally: era-server has no JDK
# and no Gradle, and it is not getting either. The only thing that crosses the
# link is a finished container image.
#
# Usage: scripts/deploy.sh [--sleep-arch] [--no-build]
set -euo pipefail

REMOTE_SRC="${REMOTE_SRC:-/home/era/build/accountability-bot}"
IMAGE="${IMAGE:-accountability-bot}"
DEPLOY_DIR="${DEPLOY_DIR:-/home/era/homelab/accountability-bot}"

# Build host addresses live in the deploy .env, not in the repository.
if [[ -f "$DEPLOY_DIR/.env" ]]; then
    # shellcheck disable=SC1091
    set -a; source "$DEPLOY_DIR/.env"; set +a
fi
BUILD_HOST="${BUILD_HOST:?set BUILD_HOST (era-arch on the LAN) in $DEPLOY_DIR/.env}"
BUILD_HOST_TS="${BUILD_HOST_TS:-}"   # era-arch over Tailscale, optional

SLEEP_ARCH=0
DO_BUILD=1
WE_WOKE_IT=0

for arg in "$@"; do
    case "$arg" in
        --sleep-arch) SLEEP_ARCH=1 ;;
        --no-build)   DO_BUILD=0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="$(date +%Y%m%d-%H%M%S)"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }

# --- pick a reachable address for era-arch, waking it if it is off ----------
pick_host() {
    for h in "$BUILD_HOST" "$BUILD_HOST_TS"; do
        [[ -n "$h" ]] || continue
        if ping -c1 -W2 "$h" >/dev/null 2>&1; then
            echo "$h"; return 0
        fi
    done
    return 1
}

if ! HOST="$(pick_host)"; then
    log "era-arch is down, sending Wake-on-LAN burst"
    /usr/local/bin/wake-pc --wait
    WE_WOKE_IT=1
    # SSH is not up the instant the network is.
    for _ in $(seq 1 30); do
        HOST="$(pick_host)" && break || sleep 2
    done
    HOST="$(pick_host)" || { echo "era-arch never came up" >&2; exit 1; }
fi
log "building on era-arch via $HOST"

if [[ "$DO_BUILD" == 1 ]]; then
    # --- ship source and build there ---------------------------------------
    log "syncing source to $HOST:$REMOTE_SRC"
    ssh "$HOST" "mkdir -p '$REMOTE_SRC'"
    rsync -az --delete \
          --exclude '.git/' --exclude 'build/' --exclude '.gradle/' --exclude '.env' \
          "$SRC_DIR/" "$HOST:$REMOTE_SRC/"

    log "docker build (this is the part that would OOM era-server)"
    ssh "$HOST" "cd '$REMOTE_SRC' && docker build -t '$IMAGE:$TAG' -t '$IMAGE:latest' ."

    # --- stream the image across the LAN, no registry involved -------------
    # Both boxes are on the same physical segment, so this runs at wire speed
    # and never touches the internet, which also avoids the degraded
    # Cloudflare path that fronts Docker Hub (NOTES.md section 0.8).
    log "transferring image"
    ssh "$HOST" "docker save '$IMAGE:$TAG' '$IMAGE:latest'" | docker load
fi

# --- deploy locally ---------------------------------------------------------
log "deploying to $DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR"
cp "$SRC_DIR/compose.yaml" "$DEPLOY_DIR/compose.yaml"

if [[ ! -f "$DEPLOY_DIR/.env" ]]; then
    echo "missing $DEPLOY_DIR/.env, copy .env.example and fill it in" >&2
    exit 1
fi

grep -q '^IMAGE_TAG=' "$DEPLOY_DIR/.env" \
    && sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$TAG/" "$DEPLOY_DIR/.env" \
    || echo "IMAGE_TAG=$TAG" >> "$DEPLOY_DIR/.env"

docker compose --project-directory "$DEPLOY_DIR" up -d
log "waiting for health"
for _ in $(seq 1 30); do
    state="$(docker inspect -f '{{.State.Health.Status}}' accountability-bot 2>/dev/null || echo starting)"
    [[ "$state" == "healthy" ]] && { log "healthy, deployed $IMAGE:$TAG"; break; }
    sleep 5
done

# --- put era-arch back to sleep, but only if we were the ones who woke it ---
# Shutting down a machine somebody is actively using is exactly the kind of
# fail-dangerous behaviour NOTES.md section 3.1 warns about.
if [[ "$SLEEP_ARCH" == 1 && "$WE_WOKE_IT" == 1 ]]; then
    log "shutting era-arch back down (we woke it)"
    ssh "$HOST" "sudo systemctl poweroff" || true
elif [[ "$SLEEP_ARCH" == 1 ]]; then
    log "leaving era-arch up, it was already on before this run"
fi
