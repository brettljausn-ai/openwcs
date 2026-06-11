# Docs agent

An automated agent that keeps user-facing documentation in sync with the code. It runs in two places:

| When | Workflow | Script | Scope |
|---|---|---|---|
| Daily (23:00 UTC) | [`.github/workflows/docs-agent.yml`](../.github/workflows/docs-agent.yml) | [`scripts/docs-agent.sh`](../scripts/docs-agent.sh) | In-repo docs — `README.md`, `docs/`, `public/` — committed directly to `main` |
| After merge to `main`  | [`.github/workflows/docs-wiki.yml`](../.github/workflows/docs-wiki.yml)  | [`scripts/docs-wiki.sh`](../scripts/docs-wiki.sh)   | The GitHub **wiki** (separate repo, pushed directly) |

Both can also be run manually from the Actions tab (**Run workflow**).

## Why two stages

The in-repo docs are swept once a day over everything that landed on `main`, so the documentation
stays current without a per-PR agent committing onto every branch. The **wiki is a separate git repo**
and cannot live in the repo, so it is synced immediately after each merge to `main`.

## What it does

### Daily on `main` (`docs-agent.sh`)

A Claude Code agent reviews **everything that landed on `main` in the last 24 hours**
(`git diff <last-commit-before-the-window>..HEAD`) and, **only if the change warrants it**:

1. Updates the in-repo docs — `README.md`, `docs/AS-BUILT.md`, `docs/DEVELOPMENT-STATUS.md`.
2. Updates the **public marketing site** (Express + EJS) — editing the source pages in
   `public/src-html/` and the strings in `public/static/i18n.js` (keeping all four languages in
   parity), then regenerating the views with `npm run build:pages` and verifying with
   `node public/static/i18n-check.js` — for user-facing capabilities only.
3. Updates `public/static/roadmap.md` when a capability's status changed.

The script then commits those edits (scoped to `README.md` / `docs/` / `public/`) directly to `main`
with a `[docs-agent]` marker. It makes no changes — and no commit — when the window has no commits, or
none with documentation impact. The diff window can be overridden with the `DOCS_AGENT_WINDOW` env var
(a `git --before` expression, default `24 hours ago`).

### After merge to `main` (`docs-wiki.sh`)

The agent reviews the merged diff (`git diff HEAD~1 HEAD`) and, only if it affects anything documented
there, edits the **wiki** pages and pushes them. New capabilities that need a page get one, linked from
`_Sidebar.md` and `Home.md`.

## Setup (one-time)

The agent runs Claude in CI, so it needs Anthropic credentials (there's no logged-in session on a
runner). Add **one** of these as a repository secret — Settings → Secrets and variables → Actions:

- **`ANTHROPIC_API_KEY`** — a standard Anthropic API key. Usage is billed per token to your Anthropic
  API account. Simplest option.
- **`CLAUDE_CODE_OAUTH_TOKEN`** — use this instead if you have a **Claude Pro/Max subscription** and
  want the runs to draw on it rather than API billing. Generate it once with `claude setup-token` and
  paste the result into the secret.

Provide just one; the workflows pass both env vars and Claude Code uses whichever is set.

### Token note (pushing to protected `main`)

The daily job pushes its docs commit **directly to `main`**, which is protected by the `guardMain`
ruleset. The default **`GITHUB_TOKEN`** cannot push to a protected branch, so add a
**`DOCS_AGENT_TOKEN`** secret — a PAT or GitHub App token with repo **`contents: write`** whose
identity is on the ruleset's **bypass list**. The workflow uses it in preference to `GITHUB_TOKEN`
(the latter is only a non-functional fallback for unprotected forks). The wiki job only needs the
built-in `GITHUB_TOKEN`.

## Loop protection

The agent commits as the author **`openwcs-docs-agent`** (matching on author, not message, so a human
commit that merely *mentions* the agent never trips a guard):

- **Wiki job** — its `if:` skips any push whose head commit author is `openwcs-docs-agent`.
- **Daily job** — it is time-triggered, so its own push to `main` does not re-trigger it. As a belt
  guard it also skips when the only commits in the window were authored by the agent.

The agent's commits also carry a `[docs-agent]` marker in the message for easy scanning of history.

## Tuning

- **Model / cost** — both run `--model sonnet` for cost efficiency; bump to `opus` in the scripts for
  higher-quality prose.
- **When the daily job runs** — the `cron` in `docs-agent.yml` is `0 23 * * *` (23:00 UTC). GitHub
  cron has no timezone or DST handling, so adjust the hour if you want a different local time.
- **Diff window** — the daily job looks back `24 hours` by default; override with the
  `DOCS_AGENT_WINDOW` env var (any `git --before` expression).
- **Scope** — the prompts in the scripts define what each stage touches and the "make no changes for
  trivial diffs" guard; edit them to widen or narrow scope.
