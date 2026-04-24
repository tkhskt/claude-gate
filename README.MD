# claude-notification

macOS menu bar app that intercepts Claude Code permission prompts and lets you
Allow / Deny from a popover without touching the terminal.

### Desktop
Run the desktop application: `./gradlew :desktopApp:run`
Run the desktop **hot reload** application: `./gradlew :desktopApp:hotRun --auto`

### Claude Code hook

Register `scripts/claude-menubar-hook.sh` as a `PermissionRequest` hook so the
running app receives permission requests.

Add to `~/.claude/settings.json` (replace the absolute path):

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
    ]
  }
}
```

When Claude Code asks for permission, the popover opens below the menu bar
icon; click **許可** to allow or **却下** to deny. If the menu bar app is not
running, the hook exits silently and Claude Code falls back to its default
terminal prompt.

The app exposes `POST http://127.0.0.1:44215/permission-request` for the hook
script. Nothing is bound outside localhost.
