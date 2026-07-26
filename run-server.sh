#!/usr/bin/env bash
# CubeWorld dev server — LEAN launcher (no gradle daemon).
#
# Why not `./gradlew runServer`? On this 7.6 GB box gradle's ~4 GB daemon on top
# of the server OOM-kills the machine (it has crashed Claude Code + servers). This
# launches the server DIRECTLY, capped, with no daemon.
#
# Two things that are easy to get wrong (each cost a past session real time):
#   1. Paper 26.2 needs Java 25. The system default `java` is 21, and Paper won't
#      even load under it ("class file version 69.0 vs 65.0"). This script
#      auto-picks a Java >= 25 (system /usr/lib/jvm/*25* first, else the gradle
#      toolchain JDK under ~/.gradle/jdks). Install a stable one with:
#        sudo apt install -y openjdk-25-jdk
#   2. The runnable jar is the paperclip BUNDLER (Main-Class io.papermc.paperclip.Main),
#      NOT run/versions/<ver>/paper-<ver>.jar — that is the *extracted* server and
#      dies with NoClassDefFoundError: joptsimple/OptionException. run-paper caches
#      the bundler at ~/.gradle/caches/run-task-jars/paper/jars/<mcver>/<build>.jar.
#
# MC version + Paper build are read from build.gradle.kts, so this tracks the pin.
#
# Usage:
#   ./gradlew build && ./run-server.sh          # foreground
#   HEAP=2000m ./run-server.sh                  # override heap (default 2600m)
# Detached dev workflow (tmux + log), from repo root:
#   tmux new-session -d -s cubeworld -x 200 -y 50
#   tmux send-keys -t cubeworld './run-server.sh 2>&1 | tee /tmp/runserver.log; exec bash' Enter
# Drive it over RCON (port 25575, pw cubeworld-dev); stop with RCON `stop` or
# `tmux kill-session -t cubeworld`.
#
# Fresh-world note: on a brand-new world the FIRST boot logs "Village anchor: no
# cubeworld structure sets found" (the cities datapack is written during that boot).
# Restart once and the 30 cities anchor on the second boot.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

MCVER="$(grep -oP 'minecraftVersion\("\K[^"]+' build.gradle.kts)"
PBUILD="$(grep -oP 'build\(\K[0-9]+' build.gradle.kts)"
HEAP="${HEAP:-2600m}"
PLUGIN="$(ls "$ROOT"/build/libs/CubeWorld-*.jar 2>/dev/null | head -1 || true)"
BUNDLER="$HOME/.gradle/caches/run-task-jars/paper/jars/$MCVER/$PBUILD.jar"

find_java25() {
  local c v
  for c in /usr/lib/jvm/*25*/bin/java "$HOME"/.gradle/jdks/*25*/bin/java; do
    [ -x "$c" ] || continue
    v="$("$c" -version 2>&1 | grep -oP '"\K[0-9]+' | head -1)"
    if [ "${v:-0}" -ge 25 ] 2>/dev/null; then echo "$c"; return 0; fi
  done
  return 1
}

JAVA="$(find_java25 || true)"
[ -n "$JAVA" ]    || { echo "ERROR: no Java >= 25 found. Install: sudo apt install -y openjdk-25-jdk" >&2; exit 1; }
[ -n "$PLUGIN" ]  || { echo "ERROR: plugin jar missing — run ./gradlew build first." >&2; exit 1; }
[ -f "$BUNDLER" ] || { echo "ERROR: Paper bundler not cached at $BUNDLER. Prime it once with './gradlew runServer' (Ctrl-C once it starts), then re-run." >&2; exit 1; }

echo ">> Java   : $JAVA"
echo ">> Paper  : $MCVER build $PBUILD   heap $HEAP"
echo ">> Plugin : $(basename "$PLUGIN")"
cd run
# Extra args are passed straight to the JVM, e.g.
#   ./run-server.sh -Dcubeworld.anchorCities=false
# to generate raw terrain with no anchored city villages.
exec "$JAVA" -Xmx"$HEAP" "$@" -jar "$BUNDLER" --nogui --add-plugin="$PLUGIN"
