#!/usr/bin/env bash
# Prints the :conversations module's changes as a unified diff against its base: the
# besteffortdev/conversation fork of Conversations (upstream 2.20.4 plus message retraction, the
# long-press message overlay, short names and app config).
#
#   tools/fork-diff.sh [ref] > fork.patch        (default: the base commit the module is on)
#   tools/fork-diff.sh --stat [ref]              (only the list of changed files)
#   UPSTREAM_DIR=<clone> tools/fork-diff.sh ...  (compare with a local checkout of the ref
#                                                 instead of fetching, e.g. when offline)
#   UPSTREAM_URL=https://codeberg.org/iNPUTmice/Conversations.git tools/fork-diff.sh 2.20.4
#                                                (against plain upstream: also the base's own
#                                                 changes)
#
# Only the source sets the module imports are compared: src/main, src/conversations, src/free,
# src/conversationsFree and libs/. Upstream's store metadata (fastlane) and launcher artwork are
# not imported and are left out. See docs/05-conversations-fork.md.
set -euo pipefail

STAT=false
if [ "${1:-}" = "--stat" ]; then
    STAT=true
    shift
fi
TAG=${1:-92d6edd3454746eaae71cae9686673c38c6c3619}
UPSTREAM_URL=${UPSTREAM_URL:-https://github.com/besteffortdev/conversation.git}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

if [ -n "${UPSTREAM_DIR:-}" ]; then
    echo "comparing with $UPSTREAM_DIR (expected at $TAG)" >&2
    mkdir -p "$WORK/upstream"
    cp -r "$UPSTREAM_DIR/src" "$UPSTREAM_DIR/libs" "$WORK/upstream/"
else
    echo "fetching $UPSTREAM_URL at $TAG" >&2
    # fetch, not clone --branch: the base is a commit. Long paths: some upstream paths exceed
    # Windows' 260 characters under a temp directory
    git init -q "$WORK/upstream"
    git -C "$WORK/upstream" fetch -q --depth 1 "$UPSTREAM_URL" "$TAG"
    git -C "$WORK/upstream" -c core.longpaths=true -c advice.detachedHead=false \
        checkout -q FETCH_HEAD
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
