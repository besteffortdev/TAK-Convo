#!/usr/bin/env bash
# The checks of docs/09-code-guidelines.md a script can make, on the plugin's code (app/):
# lines over 100 characters, wildcard imports, and catch-alls outside the boundaries docs/09
# lists. Prints what it finds; exits 1 if anything. Run before committing; CI runs it too.
#
#   tools/check-style.sh
set -euo pipefail

cd "$(dirname "$0")/.."
dirs=(app/src/main/java app/src/test/java)
status=0

report() {
    if [ -n "$2" ]; then
        echo "$1:"
        echo "$2"
        status=1
    fi
}

report "lines over 100 characters" "$(grep -rnE '^.{101,}$' "${dirs[@]}" || true)"
report "wildcard imports" "$(grep -rnE '^import (static )?[a-zA-Z0-9_.]+\.\*;' "${dirs[@]}" || true)"
# EmbeddedActivityHost.guarded and its create/relaunch: the boundary docs/09 names, which
# checked exceptions cross (Instrumentation, reflection)
report "catch-alls outside the documented boundaries" "$(grep -rnE \
    'catch \((final )?(Throwable|Exception) ' app/src/main/java \
    | grep -v '^app/src/main/java/com/atakmap/android/takconvo/plugin/ui/host/EmbeddedActivityHost.java:' \
    || true)"

if [ $status -eq 0 ]; then
    echo "style: OK"
fi
exit $status
