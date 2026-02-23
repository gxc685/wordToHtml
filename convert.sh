#!/bin/bash
# Usage: ./convert.sh [--root=. --recursive=false ...]

if ! command -v mvn &> /dev/null; then
    echo "ERROR: Maven was not found in PATH."
    exit 1
fi

ARGS="$*"
if [ -z "$ARGS" ]; then
    ARGS="--root=."
fi

echo "Running args: $ARGS"
mvn -q exec:java -Dexec.args="$ARGS"
