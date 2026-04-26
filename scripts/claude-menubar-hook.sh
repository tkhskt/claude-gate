#!/bin/bash
# Claude Code PermissionRequest hook for the claude-gate menu bar app.
# Reads the hook JSON from stdin, forwards it to the running app's local HTTP
# server, and echoes the server's JSON response back to Claude Code.
# If the app is not running the hook exits silently so Claude falls back to
# its default permission prompt.

set -u

INPUT=$(cat)
RESPONSE=$(
  curl -sS \
    -H 'Content-Type: application/json' \
    --data-binary "$INPUT" \
    http://127.0.0.1:44215/permission-request 2>/dev/null
)

if [ -z "$RESPONSE" ]; then
  exit 0
fi

printf '%s' "$RESPONSE"
