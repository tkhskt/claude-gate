# claude-notification

macOS menu bar app that intercepts Claude Code permission prompts and lets you
Allow / Deny from a popover without touching the terminal.

### Desktop
Run the desktop application: `./gradlew :desktopApp:run`
Run the desktop **hot reload** application: `./gradlew :desktopApp:hotRun --auto`

### Packaging (.dmg)

Build a distributable macOS bundle with the JRE included so end users don't
need Java installed:

```bash
./gradlew :desktopApp:packageDistributionForCurrentOS
```

Outputs land in `desktopApp/build/compose/binaries/main/dmg/`. The bundle is
unsigned. If a recipient downloaded the `.dmg` (e.g. from a browser, Slack,
or AirDrop) macOS attaches a quarantine flag and Gatekeeper blocks the first
launch — strip it after installing to `/Applications`:

```bash
xattr -dr com.apple.quarantine /Applications/claude-notification.app
```

Locally built `.dmg`s don't have the quarantine flag, so this step is only
needed for distribution.

Runtime logs are written to `~/Library/Logs/claude-notification.log`
regardless of how the app was launched (terminal or Finder).

### Claude Code hooks

Register two hooks so the app receives both permission requests and the
"tool is about to execute" signal:

- `scripts/claude-menubar-hook.sh` — `PermissionRequest`. Forwards the request
  to the running app and returns its Allow/Deny decision.
- `scripts/claude-menubar-tool-resolved.sh` — `PostToolUse` /
  `UserPromptSubmit`. `PostToolUse` clears the popover after the tool
  has actually run (i.e. the request was approved somewhere).
  `UserPromptSubmit` covers every other "the user moved on" case — most
  importantly the manual terminal-deny path, for which Claude Code fires
  no tool-level signal at all (see
  [anthropics/claude-code#37769](https://github.com/anthropics/claude-code/issues/37769)).
  The hook never influences Claude's flow. (`PreToolUse`, `PermissionDenied`,
  `Stop`, and `PostToolUseFailure` were tested and dropped: too early,
  auto-mode-only, redundant with `UserPromptSubmit`, or both.)

Add to `~/.claude/settings.json` (replace the absolute paths):

```json
{
  "hooks": {
    "PermissionRequest": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/claude-notification/scripts/claude-menubar-hook.sh"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/claude-notification/scripts/claude-menubar-tool-resolved.sh"
          }
        ]
      }
    ],
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/claude-notification/scripts/claude-menubar-tool-resolved.sh"
          }
        ]
      }
    ]
  }
}
```

When Claude Code asks for permission, the popover opens below the menu bar
icon; click **許可** to allow or **却下** to deny. If the menu bar app is not
running, the hook exits silently and Claude Code falls back to its default
terminal prompt.

The app exposes the following endpoints on `127.0.0.1:44215` (loopback only,
nothing bound outside localhost):

- `POST /permission-request` — used by the `PermissionRequest` hook.
- `POST /tool-resolved` — used by the `PostToolUse` and `UserPromptSubmit`
  hooks to dismiss popovers whose decision was made elsewhere.
