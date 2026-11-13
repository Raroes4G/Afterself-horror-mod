#!/bin/bash
# Builds the three Afterself jars and copies them to jars/. Usage: bash build-all.sh [version...]
ROOT="$(cd "$(dirname "$0")" && pwd)"
# Uses the JDK 21 in ~/.jdks when it is there, otherwise the JAVA_HOME already set.
JDK="$HOME/.jdks/jdk-21.0.13+9"
if [ -d "$JDK" ]; then
  export JAVA_HOME="$JDK"
  export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$HOME/.gradle/udtmp"
fi
versions=("$@"); [ ${#versions[@]} -eq 0 ] && versions=(1.19.2 1.20.1 1.21)
status=0
for v in "${versions[@]}"; do
  task="build"; [ "$v" = "1.21" ] && task="mergedJar"
  echo "=== $v: $task"
  (cd "$ROOT/$v" && ./gradlew --no-daemon --console=plain "$task" 2>&1 | grep -vE 'Picked up JAVA_TOOL|^$' | tail -n 60)
  if [ "${PIPESTATUS[0]}" != "0" ]; then status=1; fi
done
mkdir -p "$ROOT/jars"
# Only the current version: older jars can linger in build/libs.
VERSION=$(grep '^mod_version=' "$ROOT/1.21/gradle.properties" | cut -d= -f2)
find "$ROOT"/1.19.2/build/libs "$ROOT"/1.20.1/build/libs "$ROOT"/1.21/build/libs -maxdepth 1 -name "afterself-*-$VERSION.jar" 2>/dev/null \
  | grep -vE '(sources|javadoc|-dev)\.jar$' | while read -r j; do cp "$j" "$ROOT/jars/"; done
ls -la "$ROOT/jars"
exit $status
