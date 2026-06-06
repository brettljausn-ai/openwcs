# Docs agent

An automated agent that keeps user-facing documentation in sync with the code. It runs in two places,
so documentation lands **with** the change rather than chasing it afterwards:

| When | Workflow | Script | Scope |
|---|---|---|---|
| On every pull request | [`.github/workflows/docs-agent.yml`](../.github/workflows/docs-agent.yml) | [`scripts/docs-agent.sh`](../scripts/docs-agent.sh) | In-repo docs — `README.md`, `docs/`, `public/` — committed onto the PR branch |
| After merge to `main`  | [`.github/workflows/docs-wiki.yml`](../.github/workflows/docs-wiki.yml)  | [`scripts/docs-wiki.sh`](../scripts/docs-wiki.sh)   | The GitHub **wiki** (separate repo, pushed directly) |

Both can also be run manually from the Actions tab (**Run workflow**).

## Why two stages

The in-repo docs belong **in the PR** that changes the behaviour — so review and merge are atomic and
there is no pile-up of after-the-fact `docs/auto-*` PRs. The **wiki is a separate git repo** and cannot
be part of a PR, so it is synced once the change is actually on `main`.

## What it does

### On a pull request (`docs-agent.sh`)

A Claude Code agent reviews the **whole PR diff** (`git diff origin/<base>...HEAD`) and, **only if the
change warrants it**:

1. Updates the in-repo docs — `README.md`, `docs/AS-BUILT.md`, `docs/DEVELOPMENT-STATUS.md`.
2. Updates the **public marketing site** under `public/` (keeping `public/i18n.js` in 4-language
   parity, verified with `public/i18n-check.js`) — for user-facing capabilities only.

The script then commits those edits (scoped to `README.md` / `docs/` / `public/`) back onto the **PR
branch** with a `[docs-agent]` marker, so they merge together with the code. It makes no changes — and
no commit — for diffs with no documentation impact.

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

### Token note (required status checks)

The PR job pushes its docs commit to the PR branch. A push made with the default **`GITHUB_TOKEN`**
does **not** re-trigger workflows, so the new docs commit won't get a fresh run of your required PR
checks — and a branch ruleset that requires those checks would then block the merge until the checks
are re-run. If `main` requires status checks, add a **`DOCS_AGENT_TOKEN`** secret (a PAT or GitHub App
token with repo **`contents: write`**); the PR workflow uses it in preference to `GITHUB_TOKEN`, so the
docs commit triggers the checks like any human push. Without required checks, the `GITHUB_TOKEN`
fallback is fine. (The wiki job only needs the built-in `GITHUB_TOKEN`.)

## Loop protection

Every commit the agent makes carries the literal tag `[docs-agent]`:

- **Wiki job** — its `if:` skips any merge whose head commit contains the tag.
- **PR job** — if a `DOCS_AGENT_TOKEN` is used, the agent's own push re-triggers the PR workflow; the
  script skips when the branch tip is already a `[docs-agent]` commit, so it never loops. (With the
  `GITHUB_TOKEN` fallback the push doesn't re-trigger at all.)

The agent runs again — and adds another docs commit — whenever a human pushes more code on top, which
is the intended behaviour.

## Tuning

- **Model / cost** — both run `--model sonnet` for cost efficiency; bump to `opus` in the scripts for
  higher-quality prose.
- **When the PR job runs** — it triggers on `opened` / `synchronize` / `reopened` / `ready_for_review`
  and skips drafts. Narrow it to, say, only `ready_for_review` (or gate on a label) if you'd rather it
  run once the PR is finalised instead of on every push.
- **Scope** — the prompts in the scripts define what each stage touches and the "make no changes for
  trivial diffs" guard; edit them to widen or narrow scope.
