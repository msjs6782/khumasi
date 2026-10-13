#!/usr/bin/env bash
# Publishes CI output (logs, screenshots) to the `ci-results` branch as a single commit, so
# results can be read with git even where Actions logs and artifacts are not reachable.
# Only the current run is kept; the branch is force-pushed without history.
# Usage: ci/publish_results.sh <source-dir> <job-name>
set -u
SRC="$(cd "$1" && pwd)"
JOB="$2"
RUN="${GITHUB_RUN_NUMBER:-0}"
URL="https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"

WORK="$(mktemp -d)"
cd "$WORK" || exit 0
git init -q
git remote add origin "$URL"
if git fetch -q --depth 1 origin ci-results 2>/dev/null; then
  git checkout -q FETCH_HEAD -- . 2>/dev/null || true
fi
mkdir -p runs
find runs -mindepth 1 -maxdepth 1 ! -name "$RUN" -exec rm -rf {} + 2>/dev/null || true
mkdir -p "runs/$RUN/$JOB"
cp -r "$SRC"/. "runs/$RUN/$JOB/"
echo "$RUN $(git -C "$GITHUB_WORKSPACE" rev-parse --short HEAD 2>/dev/null)" > "runs/$RUN/commit.txt"
git checkout -q --orphan results
git add -A
git -c user.name="khumasi-ci" -c user.email="ci@users.noreply.github.com" commit -q -m "CI results run $RUN ($JOB)"
for attempt in 1 2 3; do
  if git push -q -f origin results:ci-results; then echo "published runs/$RUN/$JOB"; exit 0; fi
  sleep 3
done
echo "::warning::Could not publish CI results"
