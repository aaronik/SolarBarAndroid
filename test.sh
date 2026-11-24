#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac --release 11 -Xlint:-options -d "$out" app/src/main/java/com/aaronik/solarbar/Victron.java tests/VictronTest.java
java -cp "$out" com.aaronik.solarbar.VictronTest
