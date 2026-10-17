#!/usr/bin/env bash
# Builds the mod jar with JDK 17 (Gradle 7.6 won't run on newer Java).
set -e
cd "$(dirname "$0")"
if [ -z "$JAVA_HOME" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "17'; then
    for d in /usr/lib/jvm/java-17-openjdk /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/temurin-17-jdk* \
             /usr/lib/jvm/jdk-17* "$HOME"/.jdks/*17*; do
        if [ -x "$d/bin/java" ]; then JAVA_HOME="$d"; break; fi
    done
fi
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "JDK 17 not found. Arch: sudo pacman -S jdk17-openjdk" >&2
    exit 1
fi
export JAVA_HOME
echo "Using JDK: $JAVA_HOME"
./gradlew build --no-daemon
echo
echo "Done:"
ls -1 build/libs/fpvdrones-*.jar
