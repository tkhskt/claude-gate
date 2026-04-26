# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

All tasks are driven by the Gradle wrapper. Run from the repo root.

- Run desktop app: `./gradlew :desktopApp:run`
- Run desktop app with Compose hot reload: `./gradlew :desktopApp:hotRun --auto`
- Build everything: `./gradlew build`
- Run all tests: `./gradlew check` (or `./gradlew :sharedUI:jvmTest`)
- Run a single test class: `./gradlew :sharedUI:jvmTest --tests "com.tkhskt.claude.gate.SomeTest"`
- Package native distributions (Dmg/Msi/Deb): `./gradlew :desktopApp:packageDistributionForCurrentOS`

Gradle configuration cache and parallel builds are enabled in `gradle.properties`; if a task misbehaves, try `--no-configuration-cache`.

## Architecture

Kotlin Multiplatform project using Compose Multiplatform. Two Gradle modules:

- `sharedUI/` — KMP module containing all UI and app logic. Source sets:
  - `commonMain` holds the Composable tree (entry point `com.tkhskt.claude.gate.App`), theming (`theme/Theme.kt` exposes `AppTheme` and the `LocalThemeIsDark` composition local for dark/light toggling), and Compose resources under `commonMain/composeResources/` (drawables, strings, fonts — accessed via the generated `claude_gate.sharedui.generated.resources.Res` class).
  - `jvmMain` adds desktop-specific dependencies (`compose.desktop.currentOs`, `kotlinx-coroutines-swing`).
  - `commonTest` uses `kotlin.test` + `compose.ui.test`.
- `desktopApp/` — thin JVM Compose Desktop launcher. `desktopApp/src/main/kotlin/main.kt` defines `MainKt` (the `mainClass`) which opens a `Window` hosting `App()` from `sharedUI`. Packaging metadata (macOS bundleID `com.tkhskt.claude.gate.desktopApp`, icons in `desktopApp/appIcons/`) lives in `desktopApp/build.gradle.kts`.

Dependency versions are centralized in `gradle/libs.versions.toml` (version catalog). Key stack: Kotlin 2.3.x, Compose Multiplatform 1.10.x, Material3, kotlinx-coroutines, kotlinx-serialization, Kermit (logging), and the Metro DI plugin (`dev.zacsweers.metro`) applied to `sharedUI`. JVM target is 17.

When adding UI, put Composables and logic in `sharedUI/commonMain` so they stay platform-agnostic; only drop into `jvmMain` for Swing/AWT or desktop-only APIs. `desktopApp` should remain a minimal shell around `App()`.
