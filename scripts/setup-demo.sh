#!/usr/bin/env bash
# openWCS demo bootstrap for a fresh Ubuntu 22.04 / 24.04 server.
#
# Installs Docker Engine + the Compose plugin and OpenJDK 21, clones the repo,
# builds the service jars, and starts the full stack with Docker Compose.
# Re-runnable: existing checkouts are fast-forwarded, already-installed tools
# are skipped.
#
# Usage (as root):
#   curl -fsSL https://raw.githubusercontent.com/brettljausn-ai/openwcs/main/scripts/setup-demo.sh | bash
#   # or, from a checkout:
#   sudo ./scripts/setup-demo.sh [--auto-deploy]
#
#   --auto-deploy   also install the systemd timer that polls origin/main and
#                   redeploys whenever it changes (see deploy/README.md).
#
# Config via environment (all optional):
#   OPENWCS_REPO    git URL    (default: https://github.com/brettljausn-ai/openwcs.git)
#   OPENWCS_DIR     checkout   (default: /opt/openwcs)
#   OPENWCS_BRANCH  branch     (default: main)
set -euo pipefail

OPENWCS_REPO="${OPENWCS_REPO:-https://github.com/brettljausn-ai/openwcs.git}"
OPENWCS_DIR="${OPENWCS_DIR:-/opt/openwcs}"
OPENWCS_BRANCH="${OPENWCS_BRANCH:-main}"
INSTALL_AUTODEPLOY=0
[ "${1:-}" = "--auto-deploy" ] && INSTALL_AUTODEPLOY=1

log() { echo "$(date -Is) [setup] $*"; }
[ "$(id -u)" -eq 0 ] || { echo "Please run as root (e.g. sudo $0)"; exit 1; }

log "Installing base packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y git curl jq ca-certificates

if ! command -v docker >/dev/null 2>&1; then
  log "Installing Docker Engine + Compose plugin"
  curl -fsSL https://get.docker.com | sh
else
  log "Docker already present: $(docker --version)"
fi

if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q '"21'; then
  log "Installing OpenJDK 21"
  apt-get install -y openjdk-21-jdk
else
  log "JDK already present: $(java -version 2>&1 | head -1)"
fi

if [ -d "$OPENWCS_DIR/.git" ]; then
  log "Updating existing checkout at $OPENWCS_DIR"
  git -C "$OPENWCS_DIR" fetch --quiet origin "$OPENWCS_BRANCH"
  git -C "$OPENWCS_DIR" checkout -q "$OPENWCS_BRANCH"
  git -C "$OPENWCS_DIR" reset --hard "origin/$OPENWCS_BRANCH"
else
  log "Cloning $OPENWCS_REPO -> $OPENWCS_DIR"
  install -d "$(dirname "$OPENWCS_DIR")"
  git clone --branch "$OPENWCS_BRANCH" "$OPENWCS_REPO" "$OPENWCS_DIR"
fi

cd "$OPENWCS_DIR"

log "Building service jars (./gradlew bootJar) — first run downloads dependencies"
./gradlew bootJar --no-daemon

log "Building images and starting the stack (several minutes on first run)"
docker compose -f platform/docker-compose.yml --profile apps up --build -d --remove-orphans

if [ "$INSTALL_AUTODEPLOY" -eq 1 ]; then
  log "Installing auto-deploy systemd timer"
  sed "s#/opt/openwcs#$OPENWCS_DIR#g" deploy/openwcs-deploy.service > /etc/systemd/system/openwcs-deploy.service
  install -m 0644 deploy/openwcs-deploy.timer /etc/systemd/system/openwcs-deploy.timer
  systemctl daemon-reload
  systemctl enable --now openwcs-deploy.timer
  log "Auto-deploy enabled (polls origin/$OPENWCS_BRANCH every 2 min)"
fi

ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
log "Current stack status:"
docker compose -f platform/docker-compose.yml --profile apps ps || true

cat <<EOF

==================================================================
 openWCS demo is starting up.
   UI            : http://${ip:-localhost}/        (served by the stack; nginx proxies /api)
   Gateway / API : http://${ip:-localhost}:8080    (health: /actuator/health)
   Keycloak      : http://${ip:-localhost}:8180    (admin / admin)

 Open ports 80 (UI) and 8080 (API) in your cloud/VM firewall to reach them.
 Security is OFF by default (demo mode) — the APIs need no token.

 Manage the stack:
   docker compose -f $OPENWCS_DIR/platform/docker-compose.yml --profile apps ps
   docker compose -f $OPENWCS_DIR/platform/docker-compose.yml --profile apps logs -f gateway
   docker compose -f $OPENWCS_DIR/platform/docker-compose.yml --profile apps down       # stop
   docker compose -f $OPENWCS_DIR/platform/docker-compose.yml --profile apps down -v    # stop + wipe data
==================================================================
EOF
