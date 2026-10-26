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
#
# --no-build keeps the image that is already deployed and only refreshes
# compose.yaml, the scripts, the systemd timers and .env. The bot is not restarted unless one of
# those changed.
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

# ---------------------------------------------------------------------------
# DNS for the build
#
# A build needs the network twice: Gradle pulling from Maven Central, and apk
# pulling security updates. A build container has its own network namespace, so
# it can only use a resolver that answers from one. That rules out the host's
# systemd stub on 127.0.0.53, which is loopback and therefore means the
# container itself. It also rules out 1.1.1.1 on this network, where outbound
# DNS from a container is dropped.
#
# The normal path is whatever /etc/docker/daemon.json already gives containers,
# and then this is invisible. When that is broken, the script looks for a
# resolver that does answer from a container and builds through a dedicated
# buildx builder that carries it, so a deploy never depends on the campus
# network or on editing the daemon by hand.
#
# buildkitd runs with host networking for one specific reason: it has to pull
# the base images itself, and in the broken case the daemon's DNS is no good to
# it either. On the host, whatever the host resolves with works.
# ---------------------------------------------------------------------------

PROBE_IMAGE="${PROBE_IMAGE:-alpine:latest}"
BUILDER_NAME="accountability-build"
BUILDER_STAMP="$SRC_DIR/build/deploy-build-dns"

# Resolvers to try when the daemon's own DNS does not work, best first:
# systemd-resolved keeps the real upstreams separately from the stub it hands to
# applications, which is exactly the list worth trying. The gateway is the last
# resort. Loopback and IPv6 are dropped: loopback is the container itself, and
# this build does not need v6.
resolver_candidates() {
    { cat /run/systemd/resolve/resolv.conf /etc/resolv.conf 2>/dev/null \
        | awk '/^nameserver/ {print $2}'
      ip route 2>/dev/null | awk '/^default/ {print $3; exit}'
    } | grep -v ':' | grep -vE '^127\.' | awk '!seen[$0]++'
}

# With no arguments, asks the question that matters: can a plain container
# resolve at all? With arguments, tests specific resolvers.
container_dns_works() {
    docker image inspect "$PROBE_IMAGE" >/dev/null 2>&1 \
        || docker pull -q "$PROBE_IMAGE" >/dev/null 2>&1 \
        || return 1
    local args=()
    local ns
    for ns in "$@"; do
        args+=(--dns "$ns")
    done
    docker run --rm "${args[@]}" "$PROBE_IMAGE" nslookup dl-cdn.alpinelinux.org >/dev/null 2>&1
}

# Creates or reuses the builder. The resolver list is baked into buildkitd, so
# the builder is rebuilt only when that list changes, which keeps its layer
# cache across deploys.
ensure_builder() {
    local resolvers=("$@")
    local stamp="${resolvers[*]}"

    if [[ -f "$BUILDER_STAMP" && "$(cat "$BUILDER_STAMP")" == "$stamp" ]] \
        && docker buildx inspect "$BUILDER_NAME" >/dev/null 2>&1; then
        return 0
    fi

    log "preparing $BUILDER_NAME with resolvers: $stamp"
    docker buildx rm "$BUILDER_NAME" >/dev/null 2>&1 || true

    mkdir -p "$SRC_DIR/build"
    local config="$SRC_DIR/build/deploy-buildkitd.toml"
    {
        echo '[dns]'
        printf '  nameservers = [%s]\n' \
            "$(printf '"%s", ' "${resolvers[@]}" | sed 's/, $//')"
    } > "$config"

    docker buildx create \
        --name "$BUILDER_NAME" \
        --driver docker-container \
        --driver-opt network=host \
        --config "$config" \
        --bootstrap >/dev/null
    echo "$stamp" > "$BUILDER_STAMP"
}

build_image() {
    local tag="$1"

    if [[ -z "${DEPLOY_FORCE_BUILDER:-}" ]] && container_dns_works; then
        log "building $IMAGE:$tag"
        docker build -t "$IMAGE:$tag" -t "$IMAGE:latest" "$SRC_DIR"
        return
    fi

    local working=()
    local ns
    while read -r ns; do
        [[ -n "$ns" ]] || continue
        if container_dns_works "$ns"; then
            working+=("$ns")
        fi
    done < <(resolver_candidates)

    if [[ ${#working[@]} -eq 0 ]]; then
        {
            echo "container DNS is broken and none of these resolvers answer from a container:"
            resolver_candidates | sed 's/^/  /'
            echo "Give the build one with DEPLOY_DNS=\"1.1.1.1 8.8.8.8\" (it is tried first),"
            echo "or put a working resolver in /etc/docker/daemon.json as \"dns\": [\"<address>\"]"
            echo "and restart docker."
        } >&2
        exit 1
    fi
    if [[ -n "${DEPLOY_DNS:-}" ]]; then
        # Explicit override wins, in the order given.
        read -r -a working <<< "$DEPLOY_DNS"
    fi

    ensure_builder "${working[@]}"
    log "building $IMAGE:$tag through $BUILDER_NAME (container DNS is broken)"
    docker buildx build \
        --builder "$BUILDER_NAME" \
        --load \
        -t "$IMAGE:$tag" -t "$IMAGE:latest" \
        "$SRC_DIR"
}

log "checking $DEPLOY_HOST is reachable"
remote true

if [[ "$DO_BUILD" == 1 ]]; then
    TAG="$(date +%Y%m%d-%H%M%S)"
    build_image "$TAG"
else
    # Keep the tag that is already deployed. Compose compares the tag text, so
    # writing a different name for the same image would recreate the bot.
    TAG="$(remote "grep -m1 '^IMAGE_TAG=' '$DEPLOY_DIR/.env' 2>/dev/null" | cut -d= -f2 | tr -d "'" || true)"
    TAG="${TAG:-latest}"
    log "no build, keeping deployed tag $TAG"
fi

if remote "docker image inspect '$IMAGE:$TAG' >/dev/null 2>&1"; then
    log "$DEPLOY_HOST already has $IMAGE:$TAG, not shipping it"
else
    docker image inspect "$IMAGE:$TAG" >/dev/null 2>&1 \
        || { echo "$IMAGE:$TAG exists on neither machine, run without --no-build" >&2; exit 1; }
    # docker save ships every layer every time, about 100 MB compressed. Fine
    # over Tailscale for a deploy that happens occasionally; a registry would
    # only send the changed layers.
    log "shipping $IMAGE:$TAG"
    docker save "$IMAGE:$TAG" | zstd -T0 -3 -q | remote 'zstd -d -q | docker load'
fi

if ! remote "docker image inspect $DB_IMAGE >/dev/null 2>&1"; then
    log "shipping $DB_IMAGE (first deploy only)"
    docker image inspect "$DB_IMAGE" >/dev/null 2>&1 || docker pull "$DB_IMAGE"
    docker save "$DB_IMAGE" | zstd -T0 -3 -q | remote 'zstd -d -q | docker load'
fi

log "writing compose.yaml, scripts and .env to $DEPLOY_HOST:$DEPLOY_DIR"
remote "mkdir -p '$DEPLOY_DIR/scripts'"
remote "cat > '$DEPLOY_DIR/compose.yaml'" < "$SRC_DIR/compose.yaml"
for s in backup.sh restore.sh healthcheck.sh; do
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

# The nightly backup and the health check run from systemd timers on the
# server. Units are copied only when they differ, so a redeploy with no unit
# changes does not reload systemd. Needs passwordless sudo on the server.
log "installing systemd timers"
remote "mkdir -p '$DEPLOY_DIR/systemd'"
for u in "$SRC_DIR"/scripts/systemd/*; do
    remote "cat > '$DEPLOY_DIR/systemd/$(basename "$u")'" < "$u"
done
remote "bash -s -- '$DEPLOY_DIR'" <<'EOF'
set -euo pipefail
changed=0
for u in "$1"/systemd/*; do
    dst="/etc/systemd/system/$(basename "$u")"
    cmp -s "$u" "$dst" || { sudo install -m 644 "$u" "$dst"; changed=1; }
done
if [[ "$changed" == 1 ]]; then sudo systemctl daemon-reload; fi
sudo systemctl enable --now --quiet accountability-backup.timer accountability-health.timer
EOF

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
