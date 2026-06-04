# Docs agent

An automated agent that keeps user-facing documentation in sync with the code. It runs in CI on
**every merge to `main`** (and can be run manually from the Actions tab).

- Workflow: [`.github/workflows/docs-agent.yml`](../.github/workflows/docs-agent.yml)
- Logic / prompt: [`scripts/docs-agent.sh`](../scripts/docs-agent.sh)

## What it does

On each merge to `main`, a Claude Code agent reviews the merged diff and, **only if the change
warrants it**:

1. Updates the in-repo docs — `README.md`, `docs/AS-BUILT.md`, `docs/DEVELOPMENT-STATUS.md`.
2. Updates the **public marketing site** under `public/` (and keeps `public/i18n.js` in 4-language
   parity, verified with `public/i18n-check.js`) — for user-facing capabilities only.
3. Updates the **GitHub wiki** (separate `*.wiki.git` repo).

Because `main` is protected, the in-repo changes are opened as a **pull request** for you to review
and merge; the **wiki** (not protected) is pushed directly.

## Setup (one-time)

The agent runs Claude in CI, so it needs Anthropic credentials (there's no logged-in session on a
runner). Add **one** of these as a repository secret — Settings → Secrets and variables → Actions:

- **`ANTHROPIC_API_KEY`** — a standard Anthropic API key. Usage is billed per token to your
  Anthropic API account. Simplest option.
- **`CLAUDE_CODE_OAUTH_TOKEN`** — use this instead if you have a **Claude Pro/Max subscription** and
  want the runs to draw on it rather than API billing. Generate the token once on your machine with
  `claude setup-token` and paste the result into the secret.

Provide just one; the workflow passes both env vars and Claude Code uses whichever is set. The
built-in `GITHUB_TOKEN` (granted `contents: write` + `pull-requests: write`) handles pushing the
docs branch, opening the PR, and pushing the wiki — no extra token needed for those.

## Loop protection

The agent marks every commit it makes (in-repo and wiki) with the literal tag `[docs-agent]`. The
workflow's `if:` condition skips any push whose head commit message contains that tag, so the
agent's own merges never re-trigger it.

## Tuning

- **Model / cost** — it runs `--model sonnet` for cost efficiency; bump to `opus` in
  `scripts/docs-agent.sh` for higher-quality prose.
- **Auto-merge** — the docs PR is left for review. To merge it automatically, add
  `gh pr merge --auto --squash` after `gh pr create` in the script (the branch ruleset's required
  checks still gate it).
- **Direct-to-main** — if you'd rather skip the PR and let the agent commit docs straight to `main`,
  add the GitHub Actions bot to the branch ruleset's bypass list and change Step 5 in the script to
  push to `main` directly (keep the `[docs-agent]` marker for the loop guard).
- **Scope** — the prompt in `scripts/docs-agent.sh` defines what the agent touches and its
  "make no changes for trivial diffs" guard; edit it to widen or narrow scope.
