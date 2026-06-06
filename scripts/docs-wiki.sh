#!/usr/bin/env bash
# openWCS documentation agent — wiki mode.
#
# Runs in CI right after a change merges to main (see .github/workflows/docs-wiki.yml). The in-repo
# docs (README.md / docs/ / public/) are already kept in sync inside each PR by scripts/docs-agent.sh,
# so this script syncs ONLY the GitHub WIKI — a separate git repo that cannot be part of a PR.
# See docs/DOCS-AGENT.md.
#
# Required env:
#   ANTHROPIC_API_KEY  or  CLAUDE_CODE_OAUTH_TOKEN   Claude auth (provide one)
#   GH_TOKEN           token with wiki (contents) write — the workflow passes GITHUB_TOKEN
#   GITHUB_REPOSITORY  owner/repo (provided by Actions)
#   GITHUB_SHA         the merged commit (provided by Actions)
set -euo pipefail

SHORT_SHA="${GITHUB_SHA:0:7}"
REPO="${GITHUB_REPOSITORY}"

git config user.name 'openwcs-docs-agent'
git config user.email 'docs-agent@users.noreply.github.com'

IFS= read -r -d '' PROMPT <<PROMPT_EOF || true
You are the openWCS documentation agent, running in CI right after a change merged to main. Your ONLY
job here is the GitHub WIKI (a separate repo). The in-repo docs were already updated inside the PR, so
do NOT touch README.md, docs/, or public/. Be conservative and match the existing wiki style.

STEP 1 — Understand the change. Run \`git show --stat HEAD\` and \`git diff HEAD~1 HEAD\`. If it has no
wiki-relevant impact (test-only, refactor, CI/tooling, or a change already fully covered by the wiki),
STOP and make no changes.

STEP 2 — Sync the wiki. Clone it, edit the relevant pages, and push ONLY if you actually changed
something:
  git clone "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.wiki.git" /tmp/wiki
  # edit /tmp/wiki/*.md — e.g. Services.md, Equipment-Integration.md, the feature pages
  cd /tmp/wiki && git -c user.name='openwcs-docs-agent' -c user.email='docs-agent@users.noreply.github.com' \\
    commit -am 'docs: sync wiki for ${SHORT_SHA} [docs-agent]' && git push
  cd "${GITHUB_WORKSPACE:-\$PWD}"

Edit specific sections, do not rewrite. When a new capability needs a page that does not exist yet,
CREATE the page AND link it from _Sidebar.md and Home.md so it is reachable. The literal marker
[docs-agent] in the commit message is required (the workflow skips its own commits). Finish by printing
a one-line summary of what you updated (or "no changes").
PROMPT_EOF

echo "::group::Running wiki sync for ${SHORT_SHA}"
claude -p "$PROMPT" \
  --dangerously-skip-permissions \
  --model sonnet \
  --max-turns 60
echo "::endgroup::"
