# Security Policy

openWCS is an open-source warehouse control system. We take security seriously and appreciate
responsible disclosure. This policy explains what is supported and how to report a vulnerability.

## Supported versions

openWCS is pre-1.0 and ships continuously from the default branch. There are no maintained release
branches yet — security fixes land on `main`.

| Version | Supported |
| ------- | --------- |
| `main` (latest) | ✅ |
| Older commits / forks | ❌ |

Always run the latest `main` for security fixes.

## Reporting a vulnerability

**Please do not open a public issue, pull request, or discussion for security reports.**

Report privately via GitHub's **"Report a vulnerability"** button:

- https://github.com/brettljausn-ai/openwcs/security/advisories/new

This opens a private security advisory visible only to you and the maintainers. If you can't use
GitHub advisories, open a minimal issue asking a maintainer to contact you privately (no details).

When reporting, please include:

- a description of the issue and its impact;
- the affected component (service, gateway, UI, adapter, or deployment script) and version/commit;
- reproduction steps or a proof of concept;
- any suggested remediation.

### What to expect

- We aim to acknowledge a report within **5 business days**.
- We'll keep you updated as we investigate and work on a fix.
- Once a fix is available we'll publish a GitHub Security Advisory and credit you, unless you prefer
  to remain anonymous.

Please give us a reasonable window to remediate before any public disclosure (we target **90 days**,
sooner for actively exploited issues).

## Scope

In scope:

- The microservices under `services/`, the API `gateway/`, shared libraries under `libs/`, the
  `ui/` SPA, and the deployment/platform scripts (`platform/`, `scripts/`).
- The publicly reachable demo at `https://openwcs.brettljausn.ai`. Test only against your own local
  deployment where possible; do **not** run intrusive, destructive, or denial-of-service tests
  against the shared demo, and do not access or modify other users' data.

Out of scope:

- Findings that require a misconfigured deployment that contradicts the documented hardening
  guidance below (e.g. using the shipped dev credentials in production).
- Reports about third-party dependencies that already have an upstream advisory and patch — bump
  the dependency and open a normal PR instead.

## Security model & hardening notes

openWCS is built defense-in-depth, but **the defaults in this repo are for local development and
demos, not production**:

- **Edge auth is the trust boundary.** With `OPENWCS_SECURITY_ENABLED=true`, the API gateway
  validates a Keycloak JWT on every `/api/**` call, forwards a trusted identity
  (`X-Auth-User` / `X-Auth-Roles` / `X-Auth-Warehouses`) downstream, and strips client-supplied
  copies. It is **off by default** so the stack runs without a realm — turn it on for any shared or
  production deployment.
- **RBAC** is enforced per endpoint from a shared role→permission catalog, and **warehouse scope**
  is enforced for warehouse-bound requests (query/path at the gateway, body writes per service).
- **Dev credentials ship in the repo** (Postgres `openwcs/openwcs`, Keycloak `admin/admin`, demo
  realm users). These MUST be changed for any non-local deployment.
- **TLS**: the UI container forces HTTPS; the out-of-the-box certificate is self-signed. Use a real
  certificate (e.g. `scripts/issue-cert.sh`) in production.
- Inter-service traffic currently relies on the gateway as the boundary (no mTLS between services
  yet) — keep the internal network private.

See `docs/AS-BUILT.md` (§7a) and the wiki's Security page for the full picture.
