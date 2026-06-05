#!/usr/bin/env bash
# openWCS auto-deploy.
#
# Fast-forwards the working tree to origin/<branch> and, only if it actually
# advanced, rebuilds the service jars and (re)launches the Docker Compose stack.
#
# Safe to run repeatedly (a no-op when already up to date) and concurrently
# (self-locking via flock, so the systemd timer and a manual run can't overlap).
#
# Config via environment (all optional):
#   OPENWCS_DIR     deployment checkout (default: the repo this script lives in)
#   OPENWCS_BRANCH  branch to track     (default: main)
set -euo pipefail

REPO_DIR="${OPENWCS_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BRANCH="${OPENWCS_BRANCH:-main}"
COMPOSE_FILE="$REPO_DIR/platform/docker-compose.yml"
COMPOSE=(docker compose -f "$COMPOSE_FILE" --profile apps)

log() { echo "$(date -Is) [deploy] $*"; }

# Single-instance lock: skip if another deploy is mid-flight.
exec 9>"/tmp/openwcs-deploy.lock"
if ! flock -n 9; then
  log "another deploy is already running; skipping"
  exit 0
fi

cd "$REPO_DIR"
git fetch --quiet origin "$BRANCH"
local_rev="$(git rev-parse @)"
remote_rev="$(git rev-parse "origin/$BRANCH")"

if [ "$local_rev" = "$remote_rev" ]; then
  log "already up to date at ${local_rev:0:7}"
  exit 0
fi

log "deploying ${local_rev:0:7} -> ${remote_rev:0:7}"
git reset --hard "origin/$BRANCH"          # track main exactly; discard any local drift

log "building service jars (./gradlew bootJar)"
./gradlew bootJar --no-daemon

# Version metadata for the Go adapters' build args (Java jars stamp git via Gradle). The System
# info screen surfaces these. Java services pick up the commit from Gradle's build-info directly.
export OPENWCS_GIT_SHA="$(git rev-parse --short HEAD)"
export OPENWCS_BUILD_TIME="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

log "(re)building images and starting the stack (git ${OPENWCS_GIT_SHA})"
"${COMPOSE[@]}" up --build -d --remove-orphans

"${COMPOSE[@]}" ps
log "deploy complete at ${remote_rev:0:7}"
