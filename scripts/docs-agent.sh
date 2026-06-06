#!/usr/bin/env bash
# openWCS documentation agent — PR mode.
#
# Runs in CI on a pull request (see .github/workflows/docs-agent.yml), BEFORE it merges. It uses
# Claude Code headlessly to bring the repo's user-facing docs in sync with what the PR changes, then
# commits the result back ONTO THE PR BRANCH — so the docs land atomically with the code when the PR
# is merged (no separate, after-the-fact docs PR).
#
# The GitHub wiki is a SEPARATE git repo and cannot be part of this PR; it is synced after merge by
# scripts/docs-wiki.sh (.github/workflows/docs-wiki.yml). See docs/DOCS-AGENT.md.
#
# Required env:
#   ANTHROPIC_API_KEY  or  CLAUDE_CODE_OAUTH_TOKEN   Claude auth (provide one)
#   GH_TOKEN           token able to push to the PR head branch (see the token note in DOCS-AGENT.md)
#   PR_HEAD_REF        the PR branch name to push the docs commit back to
#   BASE_REF           the PR base branch (default: main)
set -euo pipefail

BASE_REF="${BASE_REF:-main}"
PR_HEAD_REF="${PR_HEAD_REF:-}"
MARKER='[docs-agent]'

git config user.name 'openwcs-docs-agent'
git config user.email 'docs-agent@users.noreply.github.com'

# Loop guard: if the branch tip is our own commit, the docs are already in sync for this diff.
# (Only matters when the push that triggered this run was made with a PAT, which re-triggers CI.)
if git log -1 --pretty=%B | grep -qF "$MARKER"; then
  echo "Branch tip is a docs-agent commit ($MARKER) — already in sync, skipping."
  exit 0
fi

# Make sure we can diff the PR against its base.
git fetch --no-tags origin "$BASE_REF"

IFS= read -r -d '' PROMPT <<PROMPT_EOF || true
You are the openWCS documentation agent, running in CI on a pull request BEFORE it merges. Your job is
to bring the repository's USER-FACING documentation in sync with what THIS PR changes, so the docs
merge together with the code. Be conservative: only change what the PR actually warrants, never churn
unrelated text, and match the existing style.

STEP 1 — Understand the change. Run \`git diff origin/${BASE_REF}...HEAD --stat\` and
\`git diff origin/${BASE_REF}...HEAD\` to see everything this PR changes versus the base branch. If it
has no documentation impact (test-only, a tiny refactor, a CI/tooling tweak, or a docs-only change),
STOP and make no changes.

STEP 2 — In-repo docs. Update only what the change warrants:
  - README.md (contributor welcome + the service/port table)
  - docs/AS-BUILT.md and docs/DEVELOPMENT-STATUS.md (status, the service rows, the relevant section)
  Keep the format; edit the specific rows/sections, do not rewrite.

STEP 3 — Public marketing site (ONLY for a user-facing product capability). Update the relevant page
  under public/ and public/i18n.js, keeping ALL FOUR languages (en/de/fr/es) in parity. Then run
  \`node public/i18n-check.js\` and fix any missing keys. Skip this step for internal-only changes.

Do NOT touch the GitHub wiki — it is synced separately after merge. Do NOT commit, push, or open a PR
yourself; the surrounding script commits your edits onto the PR branch. Finish by printing a one-line
summary of what you updated (or "no changes").
PROMPT_EOF

echo "::group::Running docs agent (PR mode) against origin/${BASE_REF}"
claude -p "$PROMPT" \
  --dangerously-skip-permissions \
  --model sonnet \
  --max-turns 60
echo "::endgroup::"

# Commit and push any doc changes back onto the PR branch. Scope the commit to the doc surfaces so the
# agent can never accidentally sweep unrelated working-tree changes into the PR.
if [[ -n "$(git status --porcelain -- README.md docs/ public/)" ]]; then
  if [[ -z "$PR_HEAD_REF" ]]; then
    echo "Doc changes were produced but PR_HEAD_REF is unset — cannot push. Failing." >&2
    exit 1
  fi
  git add -- README.md docs/ public/
  git commit -m "docs: sync docs with this PR ${MARKER}"
  git push origin "HEAD:${PR_HEAD_REF}"
  echo "Pushed doc updates to ${PR_HEAD_REF}."
else
  echo "No doc changes needed."
fi
