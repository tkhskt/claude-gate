---
paths:
  - "sharedUI/src/commonMain/**/*.kt"
  - "sharedUI/src/jvmMain/**/*.kt"
  - "desktopApp/src/**/*.kt"
  - "scripts/*.sh"
---

# Keep doc/spec.md in sync

The authoritative description of this app's runtime behavior lives at
`doc/spec.md`. When you add or modify user-visible behavior in the files
above (new UI state, new tool rendering, new hook contract field, new
timeout, new settings, changed activation / focus / space handling,
new tray icon semantic, new popover section, etc.), update the relevant
section of `doc/spec.md` in the same change.

Do NOT update `doc/spec.md` for pure internal refactors, bug fixes that
don't change observable behavior, test-only additions, or dependency
bumps.

When unsure, err on the side of updating.
