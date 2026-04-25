#!/bin/bash
# Claude Code PostToolUse / UserPromptSubmit hook for the claude-notification
# menu bar app. Notifies the running app that the permission has been
# resolved (PostToolUse: tool ran after allow) or abandoned (UserPromptSubmit:
# user moved on without deciding on the popover). The app uses these to
# clear popover content still showing the stale request. Hook never
# influences Claude's flow.

set -u

INPUT=$(cat)
curl -sS --max-time 5 \
  -H 'Content-Type: application/json' \
  --data-binary "$INPUT" \
  http://127.0.0.1:44215/tool-resolved >/dev/null 2>&1 || true
exit 0
