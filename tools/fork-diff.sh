#!/usr/bin/env bash
# Prints the :conversations fork's changes as a unified diff against upstream Conversations.
#
#   tools/fork-diff.sh [tag] > fork.patch        (default tag: the one the fork is based on)
#   tools/fork-diff.sh --stat [tag]              (only the list of changed files)
#   UPSTREAM_DIR=<clone> tools/fork-diff.sh ...  (compare with a local checkout of the tag
#                                                 instead of cloning, e.g. when offline)
#
# Only the source sets the fork imports are compared: src/main, src/conversations, src/free,
# src/conversationsFree and libs/. Upstream's store metadata (fastlane) and launcher artwork are
# not imported and are left out. See docs/05-conversations-fork.md.
set -euo pipefail

STAT=false
if [ "${1:-}" = "--stat" ]; then
    STAT=true
    shift
fi
TAG=${1:-2.20.4}
UPSTREAM_URL=https://codeberg.org/iNPUTmice/Conversations.git
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

if [ -n "${UPSTREAM_DIR:-}" ]; then
    echo "comparing with $UPSTREAM_DIR (expected at $TAG)" >&2
    mkdir -p "$WORK/upstream"
    cp -r "$UPSTREAM_DIR/src" "$UPSTREAM_DIR/libs" "$WORK/upstream/"
else
    echo "cloning $UPSTREAM_URL at $TAG" >&2
    # long paths: some upstream paths exceed Windows' 260 characters under a temp directory
    git -c core.longpaths=true -c advice.detachedHead=false \
        clone -q --depth 1 --branch "$TAG" "$UPSTREAM_URL" "$WORK/upstream"
fi

# diff from inside WORK so that the file names read upstream/... and fork/...
mkdir -p "$WORK/fork"
cp -r "$ROOT/conversations/src" "$ROOT/conversations/libs" "$WORK/fork/"
cd "$WORK"

EXCLUDES=(-x build -x fastlane -x new_launcher-web.png)
{
    for set in main conversations free conversationsFree; do
        if $STAT; then
            diff -rq --strip-trailing-cr "${EXCLUDES[@]}" "upstream/src/$set" "fork/src/$set" || true
        else
            diff -ruN --strip-trailing-cr "${EXCLUDES[@]}" "upstream/src/$set" "fork/src/$set" || true
        fi
    done
    if $STAT; then
        diff -rq --strip-trailing-cr "${EXCLUDES[@]}" upstream/libs fork/libs || true
    else
        diff -ruN --strip-trailing-cr "${EXCLUDES[@]}" upstream/libs fork/libs || true
    fi
}
