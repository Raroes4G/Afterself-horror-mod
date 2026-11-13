#!/bin/bash
# Runs the dev-only test client for one target and prints its results.
# Usage: bash run-client-test.sh forge-1.19.2|forge-1.20.1|neoforge-1.20.1|forge-1.21.1|neoforge-1.21.1
ROOT="$(cd "$(dirname "$0")" && pwd)"
# Uses the JDK 21 in ~/.jdks when it is there, otherwise the JAVA_HOME already set.
JDK="$HOME/.jdks/jdk-21.0.13+9"
if [ -d "$JDK" ]; then
  export JAVA_HOME="$JDK"
  export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$HOME/.gradle/udtmp"
fi
case "$1" in
  forge-1.19.2) dir=1.19.2; task=runClient; run=$ROOT/1.19.2/run ;;
  forge-1.20.1) dir=1.20.1; task=runClient; run=$ROOT/1.20.1/run ;;
  neoforge-1.20.1) dir=1.20.1; task=runClient; run=$ROOT/1.20.1/run; extra=-PafterselfNeoLegacy ;;
  forge-1.21.1) dir=1.21; task=:forge:Client; run=$ROOT/1.21/forge/runs/client ;;
  neoforge-1.21.1) dir=1.21; task=:neoforge:runClient; run=$ROOT/1.21/neoforge/run ;;
  *) echo "unknown target $1"; exit 2 ;;
esac
LOG="$ROOT/testharness/logs/$1.log"; mkdir -p "$ROOT/testharness/logs"
# Each run starts from a clean world and clean screenshots.
rm -rf "$run/saves" "$run/screenshots"
# Skips the first-launch accessibility screen; keeps the test silent from the first frame.
mkdir -p "$run"
printf 'onboardAccessibility:false\npauseOnLostFocus:false\nsoundCategory_master:0.01\n' > "$run/options.txt"
(cd "$ROOT/$dir" && timeout 1100 ./gradlew --no-daemon --console=plain -PafterselfTest $extra "$task" > "$LOG" 2>&1)
echo "--- $1 gradle exit=$?"
grep -aE 'AFTERSELFTEST (FAIL|SUMMARY)' "$LOG" | sed -E 's/^.*(AFTERSELFTEST)/\1/' | cut -c1-260
echo "passes: $(grep -acE 'AFTERSELFTEST PASS' "$LOG")  shots: $(ls "$run/screenshots" 2>/dev/null | tr '\n' ' ')"
grep -aE 'BUILD (SUCCESSFUL|FAILED)|error:|Exception in|Caused by' "$LOG" | cut -c1-200 | sort -u | head -n 12
