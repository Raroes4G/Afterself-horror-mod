#!/bin/bash
# Builds the release jars, checks their contents, then boots them on real servers.
# Usage: release-check.sh AFTERSELF_ROOT MIXTEST_ROOT
S="$1"; M="$2"; B="$M/boot.sh"; V="$M/vc"; GL="$M/gl"; J="$S/jars"
VERSION=0.4.0
J120="$J/afterself-1.20.1-forge-neoforge-$VERSION.jar"
J121="$J/afterself-1.21-1.21.1-forge-neoforge-$VERSION.jar"
J119="$J/afterself-1.19.2-forge-$VERSION.jar"
vc() { echo "$V/$1/voicechat-$1.jar"; }
gl() { echo "$GL/$1.jar"; }

rm -f "$J"/afterself-*.jar
bash "$S/build-all.sh" 2>&1 | grep -aE '^===|error:|BUILD' | cut -c1-160

echo "=== jar contents"
for j in "$J119" "$J120" "$J121"; do
  list=$(unzip -l "$j")
  echo "[$(basename "$j")] classes=$(echo "$list" | grep -c '\.class') test=$(echo "$list" | grep -c 'afterselfmod/test') mods.toml=$(echo "$list" | grep -c 'META-INF/mods.toml') neoforge.mods.toml=$(echo "$list" | grep -c 'neoforge.mods.toml') forgeEntry=$(echo "$list" | grep -c 'forge/AfterselfForge.class') neoEntry=$(echo "$list" | grep -c 'neoforge/AfterselfNeoForge.class') model=$(echo "$list" | grep -c 'soul.geo.json') animations=$(echo "$list" | grep -c 'soul.animation.json') sound=$(echo "$list" | grep -c 'screamer.ogg')"
done
ls "$J"

export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$HOME/.gradle/udtmp"
report() { # server label
  L="$M/logs/$1-$2.log"
  echo "--- $1 + $2: done=$(grep -ac 'Done (' "$L") stopped=$(grep -ac 'Stopping server' "$L") voiceServer=$(grep -ac 'Voice chat server started' "$L") plugins=$(grep -aoE 'Loaded [0-9]+ plugin' "$L" | head -n 1)"
  grep -aE 'Failure message|Missing or unsupported|NoSuchMethod|NoClassDef|ClassNotFound|Mixin apply failed|\[Server thread/ERROR|\[main/ERROR' "$L" | grep -v 'No key layers' | cut -c1-170 | sort -u | head -n 6
}

export JAVA_OVERRIDE="$HOME/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2/bin/java"
bash "$B" "$M" forge-1.20.1 25601 rel-gl4.8.4 "$J120" "$(vc forge-1.20.1-2.6.27)" "$(gl forge-1.20.1-4.8.4)" >/dev/null 2>&1; report forge-1.20.1 rel-gl4.8.4
bash "$B" "$M" forge-1.20.1 25601 rel-gl4.4.9 "$J120" "$(vc forge-1.20.1-2.6.27)" "$(gl forge-1.20.1-4.4.9)" >/dev/null 2>&1; report forge-1.20.1 rel-gl4.4.9
bash "$B" "$M" neo-1.20.1 25602 rel-gl4.4.9 "$J120" "$(vc forge-1.20.1-2.6.27)" "$(gl forge-1.20.1-4.4.9)" >/dev/null 2>&1; report neo-1.20.1 rel-gl4.4.9
bash "$B" "$M" neo-1.20.1 25602 rel-gl4.8.4 "$J120" "$(vc forge-1.20.1-2.6.27)" "$(gl forge-1.20.1-4.8.4)" >/dev/null 2>&1; report neo-1.20.1 rel-gl4.8.4
unset JAVA_OVERRIDE
bash "$B" "$M" neo-1.21 25604 rel-gl4.5.8 "$J121" "$(vc neoforge-1.21.1-2.6.27)" "$(gl neoforge-1.21-4.5.8)" >/dev/null 2>&1; report neo-1.21 rel-gl4.5.8
bash "$B" "$M" forge-1.21.1 25605 rel-gl4.9.3 "$J121" "$(vc forge-1.21.1-2.6.27)" "$(gl forge-1.21.1-4.9.3)" >/dev/null 2>&1; report forge-1.21.1 rel-gl4.9.3
bash "$B" "$M" forge-1.21.1 25605 rel-gl4.5.8 "$J121" "$(vc forge-1.21.1-2.6.27)" "$(gl forge-1.21-4.5.8)" >/dev/null 2>&1; report forge-1.21.1 rel-gl4.5.8
bash "$B" "$M" neo-1.21.1 25606 rel-gl4.9.3 "$J121" "$(vc neoforge-1.21.1-2.6.27)" "$(gl neoforge-1.21.1-4.9.3)" >/dev/null 2>&1; report neo-1.21.1 rel-gl4.9.3
# Control: GeckoLib is required, so the game must refuse to start with a clear message.
bash "$B" "$M" neo-1.21.1 25606 rel-nogl "$J121" "$(vc neoforge-1.21.1-2.6.27)" >/dev/null 2>&1; report neo-1.21.1 rel-nogl
bash "$B" "$M" forge-1.21 25603 rel-gl4.5.8 "$J121" "$(vc forge-1.21-2.5.20)" "$(gl forge-1.21-4.5.8)" >/dev/null 2>&1; echo "boot exit=$?"; report forge-1.21 rel-gl4.5.8
echo RELEASE_CHECK_DONE
