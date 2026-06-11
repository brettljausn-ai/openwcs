#!/usr/bin/env bash
# openWCS documentation agent — daily mode.
#
# Runs once a day in CI (see .github/workflows/docs-agent.yml). It uses Claude Code headlessly to
# bring the repo's user-facing docs in sync with whatever landed on `main` in the last 24 hours, then
# commits the result directly back to `main`.
#
# The GitHub wiki is a SEPARATE git repo and is synced after each merge by scripts/docs-wiki.sh
# (.github/workflows/docs-wiki.yml). See docs/DOCS-AGENT.md.
#
# Required env:
#   ANTHROPIC_API_KEY  or  CLAUDE_CODE_OAUTH_TOKEN   Claude auth (provide one)
#   GH_TOKEN           token able to push to the protected `main` branch (see DOCS-AGENT.md)
set -euo pipefail

WINDOW="${DOCS_AGENT_WINDOW:-24 hours ago}"
MARKER='[docs-agent]'

AGENT_NAME='openwcs-docs-agent'
git config user.name "$AGENT_NAME"
git config user.email 'docs-agent@users.noreply.github.com'

# Resolve the diff window: the last commit BEFORE the window opened. Everything after it is what
# landed in the window. If the repo has no commit older than the window, fall back to the root commit.
SINCE_REF="$(git rev-list -1 --before="$WINDOW" HEAD || true)"
if [[ -z "$SINCE_REF" ]]; then
  SINCE_REF="$(git rev-list --max-parents=0 HEAD | tail -1)"
fi

# If nothing was committed in the window — or the only commits are the agent's own docs commits —
# there is nothing to sync.
NEW_COMMITS="$(git log --pretty=%an "${SINCE_REF}..HEAD")"
if [[ -z "$NEW_COMMITS" ]]; then
  echo "No commits in the last window ($WINDOW) — nothing to sync."
  exit 0
fi
if ! grep -qv "^$AGENT_NAME$" <<<"$NEW_COMMITS"; then
  echo "Only the docs agent committed in the window — already in sync, skipping."
  exit 0
fi

IFS= read -r -d '' PROMPT <<PROMPT_EOF || true
You are the openWCS documentation agent, running once a day in CI on the \`main\` branch. Your job is
to bring the repository's USER-FACING documentation in sync with everything that landed on \`main\` in
the last day. Be conservative: only change what those commits actually warrant, never churn unrelated
text, and match the existing style.

STEP 1 — Understand the change. Run \`git diff ${SINCE_REF}..HEAD --stat\` and
\`git diff ${SINCE_REF}..HEAD\` to see everything that landed since the window opened. If none of it has
documentation impact (test-only, tiny refactors, CI/tooling tweaks, or docs-only changes), STOP and
make no changes.

STEP 2 — In-repo docs. Update only what the changes warrant:
  - README.md (contributor welcome + the service/port table)
  - docs/AS-BUILT.md and docs/DEVELOPMENT-STATUS.md (status, the service rows, the relevant section)
  Keep the format; edit the specific rows/sections, do not rewrite.

STEP 3 — Public marketing site (ONLY for a user-facing product capability). Update the relevant page
  under public/ and public/i18n.js, keeping ALL FOUR languages (en/de/fr/es) in parity. Then run
  \`node public/i18n-check.js\` and fix any missing keys. Skip this step for internal-only changes.

STEP 4 — Roadmap. public/roadmap.md is the single source of truth for the roadmap page
  (public/roadmap.html renders it verbatim). If the day's changes change a capability's status — a
  roadmap item ships, work starts on it, or new planned work is introduced — update the matching
  \`- [status] Title\` line there (status is done | active | planned | exploring). Keep it honest:
  never mark something \`done\` before it is built end-to-end. Don't touch roadmap.md otherwise.

Do NOT touch the GitHub wiki — it is synced separately. Do NOT commit, push, or open a PR yourself;
the surrounding script commits your edits to \`main\`. Finish by printing a one-line summary of what
you updated (or "no changes").
PROMPT_EOF

echo "::group::Running docs agent (daily mode) over ${SINCE_REF}..HEAD"
claude -p "$PROMPT" \
  --dangerously-skip-permissions \
  --model sonnet \
  --max-turns 60
echo "::endgroup::"

# Commit and push any doc changes directly to main. Scope the commit to the doc surfaces so the agent
# can never accidentally sweep unrelated working-tree changes into the commit.
if [[ -n "$(git status --porcelain -- README.md docs/ public/)" ]]; then
  git add -- README.md docs/ public/
  git commit -m "docs: daily doc sync ${MARKER}"
  git push origin HEAD:main
  echo "Pushed doc updates to main."
else
  echo "No doc changes needed."
fi
