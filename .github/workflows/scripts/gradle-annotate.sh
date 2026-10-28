#!/usr/bin/env bash
# Runs gradle with the given arguments. If it fails, turns the lines that explain the failure
# (Kotlin/Java compile errors, failed tests, "What went wrong") into a GitHub error annotation.
set -o pipefail

log=$(mktemp)
if gradle --no-daemon "$@" 2>&1 | tee "$log"; then
  exit 0
fi

summary=$(grep -E -A12 "^\* What went wrong" "$log" | head -40
          grep -E "^e: |error:|FAILED|Exception|expected:<" "$log" | head -40)
# annotations are one line; encode newlines as GitHub expects
encoded=$(printf '%s' "$summary" | head -c 60000 | sed -e 's/%/%25/g' | awk '{printf "%s%%0A", $0}')
echo "::error title=Gradle failed ($*)::${encoded}"
exit 1
