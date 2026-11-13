#!/bin/bash
# Loads the released 1.21 jar on a real client, for each of its four targets.
# The jar is the released one plus the test classes; nothing of the mod is recompiled.
# Usage: run-final-jar-test.sh AFTERSELF_ROOT VOICECHAT_FOLDER target...
S="$1"; VC="$2"; shift 2
export JAVA_HOME="$HOME/.jdks/jdk-21.0.13+9"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$HOME/.gradle/udtmp"
JH="$JAVA_HOME/bin"
RELEASE="$S/jars/afterself-1.21-1.21.1-forge-neoforge-0.4.0.jar"
# Outside build/libs: build-all.sh ships whatever it finds there.
TESTJAR="$S/1.21/build/final-jar-test/afterself-final-with-tests.jar"
LOGS="$S/testharness/logs"; mkdir -p "$LOGS"

echo "=== test classes"
(cd "$S/1.21" && ./gradlew --no-daemon --console=plain -PafterselfTest :forge:classes :neoforge:classes 2>&1 | grep -aE 'BUILD|error:' | cut -c1-160)
mkdir -p "$(dirname "$TESTJAR")"
cp "$RELEASE" "$TESTJAR"
NEO="$S/1.21/neoforge/build/classes/java/main"
FORGE="$S/1.21/forge/build/sourcesSets/main"
"$JH/jar" --update --file "$TESTJAR" -C "$(cygpath -m "$NEO")" com/afterself/test
"$JH/jar" --update --file "$TESTJAR" -C "$(cygpath -m "$FORGE")" com/afterself/test/AfterselfTestHookForge.class
echo "release classes unchanged: $(unzip -l "$RELEASE" | grep -c '\.class'), with tests: $(unzip -l "$TESTJAR" | grep -c '\.class')"

for target in "$@"; do
  case "$target" in
    forge-1.21)      task=:forge:Client; run="$S/1.21/forge/runs/client"; vc="forge-1.21-2.5.20"; args="-Pminecraft_version=1.21 -Pforge_version=51.0.33" ;;
    forge-1.21.1)    task=:forge:Client; run="$S/1.21/forge/runs/client"; vc="forge-1.21.1-2.6.27"; args="-Pgeckolib_mc=1.21.1 -Pgeckolib_version=4.9.3" ;;
    neoforge-1.21)   task=:neoforge:runClient; run="$S/1.21/neoforge/run"; vc="neoforge-1.21.1-2.6.27"; args="-Pneoforge_version=21.0.167" ;;
    neoforge-1.21.1) task=:neoforge:runClient; run="$S/1.21/neoforge/run"; vc="neoforge-1.21.1-2.6.27"; args="-Pgeckolib_mc=1.21.1 -Pgeckolib_version=4.9.3" ;;
    *) echo "unknown target $target"; continue ;;
  esac
  LOG="$LOGS/final-$target.log"
  rm -rf "$run/saves" "$run/screenshots" "$run/mods"
  mkdir -p "$run/mods"
  printf 'onboardAccessibility:false\npauseOnLostFocus:false\nsoundCategory_master:0.01\n' > "$run/options.txt"
  cp "$TESTJAR" "$run/mods/"
  cp "$VC/$vc/voicechat-$vc.jar" "$run/mods/"
  (cd "$S/1.21" && timeout 1100 ./gradlew --no-daemon --console=plain -PafterselfFinalJar $args "$task" > "$LOG" 2>&1)
  echo "--- final jar on $target (Voice Chat $vc): gradle exit=$?"
  grep -aE 'AFTERSELFTEST (FAIL|SUMMARY)' "$LOG" | sed -E 's/^.*(AFTERSELFTEST)/\1/' | cut -c1-220
  echo "  checks passed: $(grep -acE 'AFTERSELFTEST PASS' "$LOG"), failed: $(grep -acE 'AFTERSELFTEST FAIL' "$LOG")"
  grep -aE 'AFTERSELFTEST (PASS) .*(client_hears|network_mic)' "$LOG" | sed -E 's/^.*AFTERSELFTEST/ /' | cut -c1-120
  grep -aE 'BUILD (SUCCESSFUL|FAILED)|Failure message|Missing or unsupported|NoSuchMethod|NoClassDef|ClassNotFound' "$LOG" | cut -c1-180 | sort -u | head -n 8
  # Keeps a copy of the screenshots per target, since two targets share a run folder.
  rm -rf "$S/testharness/shots/$target"; mkdir -p "$S/testharness/shots/$target"
  cp "$run"/screenshots/*.png "$S/testharness/shots/$target/" 2>/dev/null
done

# Back to the normal dev setup: Forge's dev run needs Voice Chat in its mods folder, NeoForge gets it from Gradle.
rm -rf "$S/1.21/forge/runs/client/mods" "$S/1.21/neoforge/run/mods"
mkdir -p "$S/1.21/forge/runs/client/mods"
cp "$VC/forge-1.21.1-2.6.27/voicechat-forge-1.21.1-2.6.27.jar" "$S/1.21/forge/runs/client/mods/"
echo FINAL_JAR_TESTS_DONE
