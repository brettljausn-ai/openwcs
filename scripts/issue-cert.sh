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
#   scripts/issue-cert.sh <domain[,domain2,...]> <email>
#   scripts/issue-cert.sh app.openwcs.ai you@openwcs.ai
#   scripts/issue-cert.sh app.openwcs.ai,openwcs.brettljausn.ai you@openwcs.ai
#
# Pass a comma-separated list to get ONE SAN certificate that covers every name (so the
# same demo box can be reached under more than one domain without a browser warning). The
# FIRST domain is the primary/common name and the certbot lineage name; the installed
# tls.crt/tls.key carry all of them.
#
# Config via env (must match docker-compose ui volume mounts):
#   OPENWCS_TLS_DIR   cert dir nginx reads      (default: /etc/openwcs/tls)
#   OPENWCS_ACME_DIR  shared ACME webroot       (default: /etc/openwcs/acme)
#   OPENWCS_LE_DIR    certbot state             (default: /etc/openwcs/letsencrypt)
set -euo pipefail

DOMAINS_ARG="${1:?usage: issue-cert.sh <domain[,domain2,...]> <email>}"
EMAIL="${2:?usage: issue-cert.sh <domain[,domain2,...]> <email>}"
TLS_DIR="${OPENWCS_TLS_DIR:-/etc/openwcs/tls}"
ACME_DIR="${OPENWCS_ACME_DIR:-/etc/openwcs/acme}"
LE_DIR="${OPENWCS_LE_DIR:-/etc/openwcs/letsencrypt}"
COMPOSE_FILE="$(cd "$(dirname "$0")/.." && pwd)/platform/docker-compose.yml"

# Split the comma-separated list into a -d flag per domain; the first is the primary
# (the certbot lineage dir we copy the installed cert from).
IFS=',' read -ra DOMAINS <<< "$DOMAINS_ARG"
PRIMARY="${DOMAINS[0]}"
CERTBOT_DOMAINS=()
for d in "${DOMAINS[@]}"; do CERTBOT_DOMAINS+=( -d "$d" ); done

sudo_if() { if [ "$(id -u)" -ne 0 ]; then sudo "$@"; else "$@"; fi; }
sudo_if mkdir -p "$TLS_DIR" "$ACME_DIR" "$LE_DIR"

echo "$(date -Is) requesting/renewing cert for ${DOMAINS[*]} (primary $PRIMARY, webroot $ACME_DIR)"
docker run --rm \
  -v "$LE_DIR:/etc/letsencrypt" \
  -v "$ACME_DIR:/var/www/certbot" \
  certbot/certbot certonly --webroot -w /var/www/certbot \
    "${CERTBOT_DOMAINS[@]}" --cert-name "$PRIMARY" --email "$EMAIL" \
    --agree-tos --no-eff-email --non-interactive --keep-until-expiring --expand

# Install the issued cert where nginx reads it (tls.crt/tls.key), then hot-reload.
sudo_if cp "$LE_DIR/live/$PRIMARY/fullchain.pem" "$TLS_DIR/tls.crt"
sudo_if cp "$LE_DIR/live/$PRIMARY/privkey.pem"  "$TLS_DIR/tls.key"
sudo_if chmod 600 "$TLS_DIR/tls.key"

docker compose -f "$COMPOSE_FILE" --profile apps exec -T ui nginx -s reload \
  && echo "$(date -Is) installed cert for ${DOMAINS[*]} and reloaded nginx" \
  || echo "$(date -Is) cert installed; could not reload (is the ui container up?) — restart ui to pick it up"
