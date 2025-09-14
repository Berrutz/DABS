#!/bin/bash
# Runs the Java-based unit tests for the LLM service.
# Save with LF line endings; CRLF will cause Bash errors.
set -e
SCRIPT_DIR=$(dirname "$0")
BASE_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$BASE_DIR"
LIB_DIR="$BASE_DIR/lib"
mkdir -p "$LIB_DIR"

JUNIT_JAR="$LIB_DIR/junit-4.13.2.jar"
HAMCREST_JAR="$LIB_DIR/hamcrest-core-1.3.jar"

fetch_jar() {
  local url="$1"
  local target="$2"
  if [ ! -f "$target" ]; then
    echo "Scarico $(basename "$target")..."
    curl -L -o "$target" "$url"
  fi
}

fetch_jar "https://search.maven.org/remotecontent?filepath=junit/junit/4.13.2/junit-4.13.2.jar" "$JUNIT_JAR"
fetch_jar "https://search.maven.org/remotecontent?filepath=org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar" "$HAMCREST_JAR"

mkdir -p "$BASE_DIR/test-classes"
javac -cp "$LIB_DIR/*:$BASE_DIR" -d "$BASE_DIR/test-classes" \
  "$BASE_DIR/utils/LLMService.java" "$BASE_DIR/test/LLMServiceTest.java"
java -cp "$BASE_DIR/test-classes:$LIB_DIR/*:$BASE_DIR" org.junit.runner.JUnitCore LLMServiceTest

