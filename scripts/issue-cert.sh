#!/usr/bin/env bash
# Obtain (or renew) a Let's Encrypt certificate for the openWCS UI domain and install
# it where the nginx UI container reads it, then reload nginx — no downtime.
#
# Uses the HTTP-01 webroot challenge served by the running ui container at
# /.well-known/acme-challenge/ (see ui/nginx.conf). Re-runnable: certbot only renews
# when the cert is near expiry, so this is safe to run from cron.
#
# Prereqs: the stack is up, DNS A record for <domain> points at this host, and ports
# 80 + 443 are open. Run as a user that can reach the Docker daemon.
#
# Usage:
#   scripts/issue-cert.sh <domain> <email>
#   scripts/issue-cert.sh openwcs.brettljausn.ai you@brettljausn.ai
#
# Config via env (must match docker-compose ui volume mounts):
#   OPENWCS_TLS_DIR   cert dir nginx reads      (default: /etc/openwcs/tls)
#   OPENWCS_ACME_DIR  shared ACME webroot       (default: /etc/openwcs/acme)
#   OPENWCS_LE_DIR    certbot state             (default: /etc/openwcs/letsencrypt)
set -euo pipefail

DOMAIN="${1:?usage: issue-cert.sh <domain> <email>}"
EMAIL="${2:?usage: issue-cert.sh <domain> <email>}"
TLS_DIR="${OPENWCS_TLS_DIR:-/etc/openwcs/tls}"
ACME_DIR="${OPENWCS_ACME_DIR:-/etc/openwcs/acme}"
LE_DIR="${OPENWCS_LE_DIR:-/etc/openwcs/letsencrypt}"
COMPOSE_FILE="$(cd "$(dirname "$0")/.." && pwd)/platform/docker-compose.yml"

sudo_if() { if [ "$(id -u)" -ne 0 ]; then sudo "$@"; else "$@"; fi; }
sudo_if mkdir -p "$TLS_DIR" "$ACME_DIR" "$LE_DIR"

echo "$(date -Is) requesting/renewing cert for $DOMAIN (webroot $ACME_DIR)"
docker run --rm \
  -v "$LE_DIR:/etc/letsencrypt" \
  -v "$ACME_DIR:/var/www/certbot" \
  certbot/certbot certonly --webroot -w /var/www/certbot \
    -d "$DOMAIN" --email "$EMAIL" \
    --agree-tos --no-eff-email --non-interactive --keep-until-expiring

# Install the issued cert where nginx reads it (tls.crt/tls.key), then hot-reload.
sudo_if cp "$LE_DIR/live/$DOMAIN/fullchain.pem" "$TLS_DIR/tls.crt"
sudo_if cp "$LE_DIR/live/$DOMAIN/privkey.pem"  "$TLS_DIR/tls.key"
sudo_if chmod 600 "$TLS_DIR/tls.key"

docker compose -f "$COMPOSE_FILE" --profile apps exec -T ui nginx -s reload \
  && echo "$(date -Is) installed cert for $DOMAIN and reloaded nginx" \
  || echo "$(date -Is) cert installed; could not reload (is the ui container up?) — restart ui to pick it up"
