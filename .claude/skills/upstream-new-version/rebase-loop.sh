#!/bin/bash
# Drive `git rebase main` through the mechanical conflicts of a fork replay, stopping on real ones.
#
# Auto-resolved (and only these):
#   README.md          -> the replayed commit's side (the README is entirely the fork's).
#   gradle.properties  -> the replayed commit's side (the final VERSION_*/BUILD_NUMBER/firestack values
#                         are written ONCE after the rebase, so intermediate values do not matter).
#   Groovy build files deleted upstream (app/build.gradle, build.gradle, settings.gradle,
#   benchmark/build.gradle) -> `git rm`; the fork's build delta is ported to the .kts files at the end.
# Anything else: prints the stopped commit + the conflicted paths and exits 2 for a hand resolution.
# Re-run after resolving (and `git add`-ing) each stop. Exit 0 + "DONE" when the rebase has finished.
set -u
cd "$(git rev-parse --show-toplevel)"
gitdir=$(git rev-parse --git-dir)
while [ -d "$gitdir/rebase-merge" ]; do
  u=$(git ls-files -u | cut -f2 | sort -u)
  if [ -z "$u" ]; then
    GIT_EDITOR=true git rebase --continue >/dev/null 2>&1 || [ -n "$(git ls-files -u)" ] \
      || { git status --short | head; exit 1; }
    continue
  fi
  for f in README.md gradle.properties; do
    if echo "$u" | grep -qx "$f"; then
      git checkout -q --theirs "$f" && git add "$f"
      u=$(echo "$u" | grep -vx "$f")
    fi
  done
  other=$(echo "$u" | grep -vxE 'app/build.gradle|build.gradle|settings.gradle|benchmark/build.gradle')
  if [ -n "$other" ]; then
    echo "STOP at $(cat "$gitdir/rebase-merge/msgnum")/$(cat "$gitdir/rebase-merge/end"):" \
      "$(git log -1 --format='%h %s' "$(cat "$gitdir/rebase-merge/stopped-sha")")"
    echo "$other"
    exit 2
  fi
  for f in $u; do git rm -q "$f"; done
done
echo DONE
