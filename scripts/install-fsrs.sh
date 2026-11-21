#!/usr/bin/env bash
#
# Builds the FSRS scheduling library and installs it into the local Maven
# repository (~/.m2), where this project's build.gradle.kts picks it up.
#
# Why this exists: java-fsrs is MIT-licensed and maintained by the algorithm's
# own organisation, but it is not published to Maven Central yet, so it cannot
# be declared as an ordinary dependency. Run this once per machine.
#
# Usage: scripts/install-fsrs.sh

set -euo pipefail

REPO="https://github.com/open-spaced-repetition/java-fsrs.git"
COORDINATES="io.github.open-spaced-repetition:fsrs:1.0.0"
JDK="${JAVA_HOME:-$HOME/.local/jdk}"

if [ ! -x "$JDK/bin/java" ]; then
    echo "No JDK at $JDK — set JAVA_HOME or install one there (17+ required)." >&2
    exit 1
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

echo "==> Cloning $REPO"
git clone --depth 1 --quiet "$REPO" "$workdir/java-fsrs"

cd "$workdir/java-fsrs"

# The upstream test suite is the reason this library is trusted rather than
# reimplemented. A failure here means the dependency should not be installed.
echo "==> Running upstream tests"
JAVA_HOME="$JDK" ./gradlew test --no-daemon --console=plain --quiet

echo "==> Publishing $COORDINATES to ~/.m2"
JAVA_HOME="$JDK" ./gradlew publishToMavenLocal \
    --no-daemon --console=plain --quiet -PsignAllPublications=false

echo "==> Done: $(find "$HOME/.m2/repository/io/github/open-spaced-repetition" -name '*.jar' | head -1)"
