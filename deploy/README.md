# Demo deployment & auto-deploy

Scripts and unit files for running an openWCS **demo** on a single Ubuntu
server and keeping it automatically up to date with `main`.

> Single-box demo — security off by default; not hardened for production. The UI
> is served over **HTTPS** (self-signed by default — see [§3 HTTPS / TLS](#3-https--tls-on-the-ui)).
> For cloud/production topologies see the wiki **Deployment Guide**.

| File | Purpose |
|------|---------|
| [`scripts/setup-demo.sh`](../scripts/setup-demo.sh) | One-shot bootstrap of a fresh server: installs Docker + JDK 21, clones, builds jars, starts the stack. |
| [`scripts/deploy.sh`](../scripts/deploy.sh) | Idempotent redeploy: fast-forward `main`, and *if it advanced* rebuild jars + recompose. Self-locking. |
| `deploy/openwcs-deploy.service` / `.timer` | systemd timer that runs `deploy.sh` every 2 min (poll-based auto-deploy). |
| [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) | GitHub Actions deploy job for a self-hosted runner, gated on green CI (push-based auto-deploy). |

---

## 1. First-time setup on a fresh server

Recommended box: **Ubuntu 22.04/24.04, ≥ 4 vCPU, ≥ 16 GB RAM, ≥ 30 GB disk**
(the stack is ~22 containers).

As **root** on the new server:

```bash
curl -fsSL https://raw.githubusercontent.com/brettljausn-ai/openwcs/main/scripts/setup-demo.sh | bash
```

This installs everything, clones to `/opt/openwcs`, builds the service jars, and
starts the full stack. When it finishes it prints the URLs:

- **Gateway / API** — `http://<server>:8080` (health at `/actuator/health`)
- **Keycloak** — `http://<server>:8180` (admin `admin` / `admin`)
- **UI (dev)** — `cd /opt/openwcs/ui && npm ci && npm run dev -- --host 0.0.0.0` → `:5173`

To bootstrap **and** turn on auto-deploy in one step, clone first and pass the flag:

```bash
git clone https://github.com/brettljausn-ai/openwcs.git /opt/openwcs
sudo /opt/openwcs/scripts/setup-demo.sh --auto-deploy
```

Override defaults with env vars: `OPENWCS_DIR`, `OPENWCS_BRANCH`, `OPENWCS_REPO`.

---

## 2. Auto-deploy — pick one

Both call the same `scripts/deploy.sh`, which is a no-op unless `main` actually
moved, and self-locks so runs never overlap.

### Option A — poll on a timer (simplest; no GitHub config, no inbound ports)

Best for a firewalled box. Latency ≈ 2 min.

```bash
sudo install -m 0644 /opt/openwcs/deploy/openwcs-deploy.service /etc/systemd/system/
sudo install -m 0644 /opt/openwcs/deploy/openwcs-deploy.timer   /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now openwcs-deploy.timer

# watch it / inspect
systemctl list-timers openwcs-deploy.timer
journalctl -u openwcs-deploy.service -f
```

(If you cloned somewhere other than `/opt/openwcs`, edit the `ExecStart=` path in
the `.service` file first — or just use `setup-demo.sh --auto-deploy`, which
rewrites it for you.)

### Option B — deploy on push, gated on green CI (event-driven; recommended)

A **self-hosted GitHub Actions runner** on the server runs the deploy job after
the **CI** workflow passes on `main`. The runner polls GitHub outbound, so no
inbound ports or SSH keys are needed, and a broken build never reaches the demo.

1. **Register the runner** — in GitHub: *Settings → Actions → Runners → New
   self-hosted runner*. Follow the shown `./config.sh --url … --token …`, then
   install it as a service:

   ```bash
   cd ~/actions-runner
   sudo ./svc.sh install root
   sudo ./svc.sh start
   ```

2. **Done** — `.github/workflows/deploy.yml` is already in the repo. On the next
   successful CI run on `main` it triggers `scripts/deploy.sh` on the server. You
   can also run it manually from the **Actions → Deploy demo → Run workflow**
   button.

> Until a runner is registered the `deploy` job just queues (harmless). Use
> Option A **or** B, not both.

---

## 3. HTTPS / TLS on the UI

The UI container (nginx) **forces HTTPS**: port **443** serves the SPA and proxies
`/api`, `/realms`, `/admin`, `/resources`; port **80** issues a `301` redirect to
`https://`. Browse the demo at **`https://<server>/`**.

**Open the firewall for 443** (in addition to 80 for the redirect):

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
```

(Internal upstreams — gateway/keycloak — stay plain HTTP on the compose network;
only the edge is TLS, which is fine.)

### Self-signed by default (out-of-the-box)

A raw-IP demo has no domain, so Let's Encrypt can't issue a cert. If no real cert
is mounted, the container **auto-generates a self-signed cert** on start (into
`/etc/nginx/tls/tls.crt` + `tls.key`). Browsers will show a **one-time warning**
("Your connection is not private") when you visit `https://<IP>/` — click
*Advanced → Proceed*. This is expected and harmless for a demo. HSTS is **not**
enabled, so accepting the exception won't lock you out later.

Add your host's IP/DNS to the cert's SAN list via the `TLS_SAN` env on the `ui`
service, e.g. `TLS_SAN: "IP:203.0.113.5"` (the cert already covers `localhost`).

### Supplying a real cert

If you have a real cert (e.g. an internal CA, or a domain with Let's Encrypt),
mount a directory containing `tls.crt` + `tls.key` over the cert path — the
entrypoint detects existing files and **uses them as-is** (never overwrites):

```yaml
# platform/docker-compose.yml, ui service
volumes:
  - /etc/openwcs/tls:/etc/nginx/tls:ro
```

The cert path is overridable with `TLS_CERT_DIR` (default `/etc/nginx/tls`). If
you front the demo with a real domain, point DNS at the server and drop the
domain's cert/key into the mounted dir as `tls.crt`/`tls.key`.

---

## 4. Manual redeploy / operations

```bash
# redeploy now (no-op if main hasn't moved)
sudo /opt/openwcs/scripts/deploy.sh

# stack controls
cd /opt/openwcs
docker compose -f platform/docker-compose.yml --profile apps ps
docker compose -f platform/docker-compose.yml --profile apps logs -f gateway
docker compose -f platform/docker-compose.yml --profile apps down       # stop (keep data)
docker compose -f platform/docker-compose.yml --profile apps down -v    # stop + wipe Postgres volume
```

## Notes

- `deploy.sh` does `git reset --hard origin/main` — the server tracks `main`
  exactly, so **don't hand-edit files on the box**; they'll be discarded.
- `docker compose up --build` uses the layer cache: only services whose code
  changed rebuild, and only changed containers are recreated (a brief per-service
  blip — fine for a demo).
- The first deploy after a large change can take a few minutes (jar + image
  builds). The `flock` lock prevents overlapping runs.
- To enable login/RBAC for a more realistic demo, set
  `OPENWCS_SECURITY_ENABLED=true` and use the Keycloak realm on `:8180`
  (`admin`/`admin`). See the wiki **Security** page.
