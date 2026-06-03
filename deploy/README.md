# Demo deployment & auto-deploy

Scripts and unit files for running an openWCS **demo** on a single Ubuntu
server and keeping it automatically up to date with `main`.

> Single-box demo — **no TLS, security off by default**. Not hardened for
> production. For cloud/production topologies see the wiki **Deployment Guide**.

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

## 3. Manual redeploy / operations

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
