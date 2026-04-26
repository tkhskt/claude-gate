# claude-gate

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
xattr -dr com.apple.quarantine /Applications/claude-gate.app
```

Locally built `.dmg`s don't have the quarantine flag, so this step is only
needed for distribution.

Runtime logs go to standard output / standard error. Launch via
`./gradlew :desktopApp:run` to see them in the terminal. Finder/Launchpad
launches inherit the OS's stdout (effectively dropped) — run from a terminal
when diagnosing.

### Claude Code hooks (recommended: install the marketplace plugin)

This repository doubles as a Claude Code plugin marketplace. Installing the
`claude-gate` plugin auto-registers the three hooks against bundled scripts —
no manual `~/.claude/settings.json` edits.

```shell
/plugin marketplace add tkhskt/claude-gate
/plugin install claude-gate@claude-gate
```

The plugin only wires up the hooks. Build and run the desktop app separately
(see above). When Claude Code asks for permission, the popover opens below
the menu bar icon; click **Allow** to allow or **Deny** to deny. If the menu
bar app is not running, the hook exits silently and Claude Code falls back
to its default terminal prompt.

### Hooks (manual setup, alternative)

If you'd rather wire the hooks by hand instead of installing the plugin, the
scripts ship at `plugins/claude-gate/scripts/`:

- `plugins/claude-gate/scripts/claude-menubar-hook.sh` — `PermissionRequest`.
  Forwards the request to the running app and returns its Allow/Deny
  decision.
- `plugins/claude-gate/scripts/claude-menubar-tool-resolved.sh` —
  `PostToolUse` / `UserPromptSubmit`. `PostToolUse` clears the popover after
  the tool has actually run (i.e. the request was approved somewhere).
  `UserPromptSubmit` covers every other "the user moved on" case — most
  importantly the manual terminal-deny path, for which Claude Code fires no
  tool-level signal at all (see
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
            "command": "/absolute/path/to/claude-gate/plugins/claude-gate/scripts/claude-menubar-hook.sh"
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/claude-gate/plugins/claude-gate/scripts/claude-menubar-tool-resolved.sh"
          }
        ]
      }
    ],
    "UserPromptSubmit": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "/absolute/path/to/claude-gate/plugins/claude-gate/scripts/claude-menubar-tool-resolved.sh"
          }
        ]
      }
    ]
  }
}
```

The app exposes the following endpoints on `127.0.0.1:44215` (loopback only,
nothing bound outside localhost):

- `POST /permission-request` — used by the `PermissionRequest` hook.
- `POST /tool-resolved` — used by the `PostToolUse` and `UserPromptSubmit`
  hooks to dismiss popovers whose decision was made elsewhere.
