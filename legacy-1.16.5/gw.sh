#!/usr/bin/env bash
# Run this build's Gradle wrapper on the right host JDK (21) regardless of the shell's default java.
# Usage: ./gw.sh <gradle args...>      e.g. ./gw.sh build    ./gw.sh :forge:runServer
# Override the JDK with SC_JAVA_HOME=/path/to/jdk21 if it is installed elsewhere.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${SC_JAVA_HOME:-/c/Program Files/Java/jdk-21.0.2}"
exec ./gradlew "$@"
