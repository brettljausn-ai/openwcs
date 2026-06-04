#!/usr/bin/env bash
# openWCS documentation agent.
#
# Runs in CI right after a change merges to main (see .github/workflows/docs-agent.yml). It uses
# Claude Code headlessly to keep the project's user-facing docs in sync with the code that just
# merged: in-repo docs + the public marketing site (opened as a PR, since main is protected) and
# the GitHub wiki (a separate, unprotected repo — pushed directly).
#
# Required env:
#   ANTHROPIC_API_KEY  Claude API key (repo secret)
#   GH_TOKEN           token with repo + wiki write + PR write (the workflow passes GITHUB_TOKEN)
#   GITHUB_REPOSITORY  owner/repo (provided by Actions)
#   GITHUB_SHA         the merged commit (provided by Actions)
set -euo pipefail

SHORT_SHA="${GITHUB_SHA:0:7}"
BRANCH="docs/auto-${SHORT_SHA}"
REPO="${GITHUB_REPOSITORY}"

git config user.name 'openwcs-docs-agent'
git config user.email 'docs-agent@users.noreply.github.com'

PROMPT=$(cat <<PROMPT_EOF
You are the openWCS documentation agent, running in CI immediately after a change merged to main.
Keep the project's USER-FACING documentation in sync with what just merged. Be conservative: only
change what the merge actually warrants, never churn unrelated text, and match the existing style.

STEP 1 — Understand the change. Run \`git show --stat HEAD\` and \`git diff HEAD~1 HEAD\` to see what
merged. If it has no documentation impact (test-only, a tiny refactor, a CI/tooling tweak, or a
docs-only commit), STOP and make no changes.

STEP 2 — In-repo docs. Update only what the change warrants:
  - README.md (contributor welcome + the service/port table)
  - docs/AS-BUILT.md and docs/DEVELOPMENT-STATUS.md (status, the service rows, the relevant section)
  Keep the format; edit the specific rows/sections, don't rewrite.

STEP 3 — Public marketing site (only if the change is a user-facing product capability). Update the
  relevant page under public/ and public/i18n.js, keeping ALL FOUR languages (en/de/fr/es) in parity.
  Then run \`node public/i18n-check.js\` and fix any missing keys. Skip this step for internal-only changes.

STEP 4 — Wiki (separate repo). If the change affects anything documented there, clone, edit, and PUSH:
  git clone "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.wiki.git" /tmp/wiki
  # edit the relevant /tmp/wiki/*.md pages (e.g. Services.md, Security.md, the feature pages)
  cd /tmp/wiki && git -c user.name='openwcs-docs-agent' -c user.email='docs-agent@users.noreply.github.com' \\
    commit -am 'docs: sync wiki for ${SHORT_SHA} [docs-agent]' && git push
  cd "${GITHUB_WORKSPACE:-$PWD}"
  (Only push if you actually changed wiki pages.)

STEP 5 — Open a PR for the in-repo changes (NEVER push to main; it is protected). If and only if you
  edited files in README.md / docs/ / public/:
  git checkout -b ${BRANCH}
  git commit -am 'docs: auto-sync for ${SHORT_SHA} [docs-agent]'
  git push -u origin ${BRANCH}
  gh pr create --base main --head ${BRANCH} \\
    --title 'docs: auto-sync for ${SHORT_SHA} [docs-agent]' \\
    --body 'Automated documentation & marketing-site sync for the change merged in ${SHORT_SHA}. Please review and merge.'
  If you made no in-repo edits, do not create a branch or PR.

The literal marker [docs-agent] in commit messages is REQUIRED — the workflow skips its own commits
to avoid an infinite loop. Finish by printing a one-line summary of what you updated (or "no changes").
PROMPT_EOF
)

echo "::group::Running docs agent for ${SHORT_SHA}"
claude -p "$PROMPT" \
  --dangerously-skip-permissions \
  --model sonnet \
  --max-turns 60
echo "::endgroup::"
