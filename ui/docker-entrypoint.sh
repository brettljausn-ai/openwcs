#!/bin/sh
# Entry point for the nginx UI container.
#
# Ensures a TLS cert/key exists before nginx starts, then hands off to nginx.
#
# Cert strategy:
#   * If a real cert is mounted at $TLS_CERT_DIR (tls.crt + tls.key), use it as-is
#     (we never overwrite mounted/existing files).
#   * Otherwise, generate a self-signed cert so TLS works out-of-the-box. The cert
#     carries a broad SAN set (localhost + loopback IPs + a wildcard) so it at
#     least loads for an IP-only/no-domain demo; browsers will still warn once,
#     which is expected for self-signed certs.
#
# Env:
#   TLS_CERT_DIR  directory holding tls.crt / tls.key   (default: /etc/nginx/tls)
#   TLS_CN        Common Name for the generated cert     (default: localhost)
#   TLS_SAN       extra SAN entries, comma-separated      (e.g. "IP:203.0.113.5,DNS:wcs.example.com")
set -eu

TLS_CERT_DIR="${TLS_CERT_DIR:-/etc/nginx/tls}"
TLS_CN="${TLS_CN:-localhost}"
CRT="${TLS_CERT_DIR}/tls.crt"
KEY="${TLS_CERT_DIR}/tls.key"

mkdir -p "${TLS_CERT_DIR}"

if [ -f "${CRT}" ] && [ -f "${KEY}" ]; then
    echo "[entrypoint] Using existing TLS cert at ${CRT}"
else
    echo "[entrypoint] No TLS cert found at ${CRT}; generating a self-signed cert."
    # Default SAN set: works for localhost and IP-only access. Callers can append
    # their host's real IP/DNS via TLS_SAN. The wildcard helps if a domain is used.
    SAN="DNS:localhost,DNS:*.localhost,IP:127.0.0.1,IP:0.0.0.0"
    if [ -n "${TLS_SAN:-}" ]; then
        SAN="${SAN},${TLS_SAN}"
    fi
    openssl req -x509 -nodes -newkey rsa:2048 \
        -days 3650 \
        -keyout "${KEY}" \
        -out "${CRT}" \
        -subj "/CN=${TLS_CN}" \
        -addext "subjectAltName=${SAN}"
    chmod 600 "${KEY}"
    echo "[entrypoint] Self-signed cert generated (CN=${TLS_CN}, SAN=${SAN})."
fi

exec nginx -g 'daemon off;'
