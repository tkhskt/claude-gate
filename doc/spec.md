# claude-notification — 仕様

macOS メニューバー常駐アプリ。Claude Code の `PermissionRequest` フックから
tool 実行の許可リクエストを横取りし、ターミナルを開かずにポップオーバー UI
で Allow / Deny を操作可能にする。

---

## 1. 前提・スコープ

| 項目         | 値 |
|--------------|----|
| 対応 OS      | macOS（Retina 含む）。JDK 17+。非 Mac は tray / NSApp 周りは no-op |
| 対象フック   | Claude Code の `PermissionRequest` イベントのみ |
| UI 技術      | Compose Multiplatform 1.10.x / Material3 / Kotlin 2.3.x |
| 非対応       | Windows, Linux（ビルド自体は通るがトレイ・アクティベーション周りは動作保証なし） |

## 2. アーキテクチャ

```
┌──────────────── Claude Code プロセス ────────────────┐
│  PermissionRequest 発火                               │
│    ↓                                                  │
│  scripts/claude-menubar-hook.sh (curl, --max-time 600)│
└────────────────────────┬──────────────────────────────┘
                         │ POST JSOrulN
                         ↓
┌──────── claude-notification プロセス (Compose Desktop) ────────┐
│  HttpServer  127.0.0.1:44215                                   │
│    ├─ Handler ──► PermissionRequestHolder.submit ─► deferred    │
│    │                                                            │
│  Compose UI                                                     │
│    ├─ TrayIcon (AWT)                                            │
│    └─ Popover Window ─► PopoverContent                          │
│         └── Allow/Deny ─► deferred.complete(decision)           │
│                                                                 │
│  MacApp (JNA)                                                   │
│    ├─ [NSApp activateIgnoringOtherApps:YES]                     │
│    └─ [NSApp isActive] ポーリング                               │
└─────────────────────────────────────────────────────────────────┘
```

- **Kotlin Multiplatform**: `sharedUI` (commonMain / jvmMain / commonTest) + `desktopApp` (thin entry)。
- **IPC**: `com.sun.net.httpserver.HttpServer`（JDK 標準、依存なし）。
- **macOS 固有呼び出し**: JNA 経由で libobjc.A + libSystem（`dispatch_async_f`）。

## 3. コンポーネント

| ファイル                                                                           | 役割 |
|-----------------------------------------------------------------------------------|------|
| `sharedUI/.../permission/PermissionRequest.kt`                                    | `PermissionRequest` 等 hook input の `@Serializable` DTO |
| `sharedUI/.../permission/PermissionRequestHolder.kt`                              | 状態ホルダー。`submit / allow / deny / togglePopover / dismissTimeout` |
| `sharedUI/.../permission/HookResponse.kt`                                         | `hookSpecificOutput.decision.behavior` JSON 生成 |
| `sharedUI/.../popover/PopoverContent.kt`                                          | ポップオーバー本体 Composable |
| `sharedUI/.../popover/LineDiff.kt`                                                | LCS ベース行 diff + コンテキスト省略 (`compactDiff`) |
| `sharedUI/.../server/PermissionServer.kt`                                         | HTTP ハンドラ |
| `sharedUI/.../desktop/DesktopApp.kt`                                              | ApplicationScope 直下の全体合成、Tray・Window・Effect |
| `sharedUI/.../desktop/mac/MacApp.kt`                                              | AppKit main thread 経由の NSApp アクティベート／isActive |
| `sharedUI/.../desktop/awt/TrayNpeSuppression.kt`                                  | JDK の TrayIcon→LightweightDispatcher NPE を黙らせる EventQueue shim |
| `desktopApp/src/main/kotlin/main.kt`                                              | エントリポイント |
| `scripts/claude-menubar-hook.sh`                                                  | hook 登録用 shell。curl で localhost に POST、失敗時は空で exit 0 |

## 4. Claude Code フック統合

### 4.1 入力（受信）

- イベント名: `PermissionRequest`
- Body: Claude Code が hook に stdin で渡す JSON。`session_id` / `transcript_path` / `cwd` / `permission_mode` / `hook_event_name` / `tool_name` / `tool_input` / `permission_suggestions`。
- 通信: 単一 TCP 接続。リクエストごとに新規。

### 4.2 出力（送信）

- 成功: HTTP 200 / `Content-Type: application/json`、body は
  ```json
  {"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}
  ```
  または `behavior: "deny"` + `message`。
- タイムアウト: HTTP 503 / body 空。hook script は `[ -z "$RESPONSE" ]` で exit 0 → Claude Code のデフォルトプロンプトへフォールバック。
- 400: JSON パース失敗時（ほぼ発生しない）。
- サーバー停止時: hook が `curl: (7) Failed to connect` → exit 0 → 同上フォールバック。

### 4.3 セットアップ（ユーザー側）

`~/.claude/settings.json`:
```json
{
  "hooks": {
    "PermissionRequest": [
      {
        "hooks": [
          { "type": "command", "command": "/absolute/path/to/scripts/claude-menubar-hook.sh" }
        ]
      }
    ]
  }
}
```

## 5. ランタイム仕様

### 5.1 状態モデル（`PermissionRequestHolder`）

| Flow                                  | 型 | 意味 |
|---------------------------------------|-----|------|
| `pending: StateFlow<PendingRequest?>` | UI 表示中の「このリクエストの Allow/Deny 待ち」 |
| `popoverVisible: StateFlow<Boolean>`  | ポップオーバー表示中か（手動 toggle / auto-open / アクティブ外 close と連動） |
| `lastTimeout: StateFlow<PermissionRequest?>` | 直近タイムアウトしたリクエスト（stick。`dismissTimeout` or 新リクエスト到着で解除） |
| `timeouts: SharedFlow<PermissionRequest>` | タイムアウト発火イベント（通知・トレイ色変更トリガ） |

### 5.2 リクエスト処理フロー

```
HTTP POST /permission-request
  ↓
Handler.handle(exchange)
  ↓
holder.submit(request)  [suspends]
  ├── submitMutex.withLock で直列化
  ├── _pending = PendingRequest(req, deferred)
  ├── _popoverVisible = true
  ├── withTimeout(580_000) { deferred.await() }
  │     ├── ユーザー Allow/Deny → deferred.complete(decision) → return
  │     └── 580s 経過 → TimeoutCancellationException
  │            ├── _lastTimeout = request
  │            └── _timeouts.tryEmit(request)
  └── finally: _pending=null, _popoverVisible=false
  ↓
exchange に 200+JSON または 503 を書き戻す
```

### 5.3 並行リクエスト

- **同時複数到着**: `submitMutex` で UI 表示は直列化。各 submit 呼び出しは自分専用の `CompletableDeferred` を持つため、`allow/deny` は「現在表示中」のリクエストの deferred のみ完了させる。対応表:
  - A 到着 → 表示 → ユーザー判定 → A の exchange に応答 → mutex 解放
  - B 到着（A 処理中）→ mutex 待ち → A 完了後に UI 切替 → B 判定 → B の exchange に応答
- **執行スレッド**: `HttpServer` は `Executors.newFixedThreadPool(4)`。5 件目以降は TCP accept queue で待機（機能的には FIFO 処理）。
- **応答の取り違えは発生しない**: 各ハンドラの `exchange` / `deferred` は呼び出しローカル。

### 5.4 ユーザー操作

| 操作                              | 挙動 |
|-----------------------------------|------|
| トレイアイコン単クリック          | ポップオーバー表示 ↔ 非表示（内容保持）。現在マウス位置を anchor にキャッシュ |
| Allow                             | `hookSpecificOutput.decision.behavior = "allow"` 返却 |
| Deny                              | `behavior = "deny"` + `message = "Denied from menu bar app"` 返却 |
| ポップオーバー外クリック          | アプリ非アクティブ化 → ポーリングが検知 → `setPopoverVisible(false)`。`_pending` は残るため再度トレイクリックで復帰可 |
| Quit ボタン                       | `kotlin.system.exitProcess(0)` |
| Dismiss ボタン（TimeoutView）     | `dismissTimeout()` で `_lastTimeout` クリア、通常状態へ |
| テキストドラッグ選択 + Cmd+C      | リクエスト本文（Tool / cwd / Agent / File / tool_input）がコピー可能。ヘッダー・ボタン等 UI chrome は選択外 |
| ウィンドウ端ドラッグ              | 手動リサイズ可（`resizable = true`） |

### 5.5 ポップオーバーウィンドウ属性

| 属性               | 値 |
|--------------------|----|
| サイズ（初期）     | 360×420 dp（narrow）、Edit/Write 時 680×620 dp（wide、`isWide` トリガ） |
| サイズ（変化）     | tool カテゴリ narrow↔wide 切替時のみ自動適用。ユーザーの手動リサイズは保持 |
| 位置               | トレイクリック時の `MouseEvent.locationOnScreen` をキャッシュ、auto-open 時も再利用。未キャッシュ時は画面右上フォールバック |
| 装飾               | `undecorated = true, transparent = true, alwaysOnTop = true, focusable = true` |
| 表示制御           | `renderVisible` を `visibleTarget` から 1 tick 遅らせ、位置確定後に true（初回表示のチラつき抑止） |

### 5.6 macOS アクティベーション検知

`undecorated + transparent + alwaysOnTop` の AWT NSWindow は `canBecomeKeyWindow = NO` のため `WindowFocusListener` は不安定。代わりに:

1. 表示時: `MacApp.activateApp()` → `[NSApp activateIgnoringOtherApps:YES]`
2. 150ms ごとに `[NSApp isActive]` ポーリング（`LaunchedEffect(renderVisible)` 内のコルーチン）
3. false を観測したら `setPopoverVisible(false)`
4. `renderVisible = false` になるとコルーチンはキャンセルされポーリング停止

JNA 呼び出しは `dispatch_async_f` + `_dispatch_main_q` で AppKit main thread に乗せる（Swing EDT ≠ AppKit main on JDK 17+）。

### 5.7 Space 切替

canJoinAllSpaces / window level 操作を試みたが、Space 切替時の一時的 deactivation との両立が困難だったため**現状 Space 追従は非対応**（ポップオーバーは元の Space に残り、切替で見えなくなる）。

## 6. UI 表示仕様

### 6.1 トレイアイコン

- サイズ 38×24（画像）、角丸半径 6。白い丸の輪郭＋中央ドット（常に白）。
- 背景色:
  - IDLE（pending=null, lastTimeout=null）: 透明
  - AWAITING（pending≠null）: amber 500 (`#FFC107`)
  - TIMEOUT（lastTimeout≠null かつ pending=null）: red 500 (`#EF4444`)
- 切替: `combine(pending, lastTimeout)` を `LaunchedEffect` で監視 → `SwingUtilities.invokeLater { icon.image = … }`
- 既知の制約: 塗り領域 < クリック highlight 領域（AppKit 固有の padding）。詳細は `TODO.local.md`。

### 6.2 ポップオーバー状態切替

| `pending` | `lastTimeout` | 表示                      |
|-----------|---------------|---------------------------|
| ≠ null    | *             | `RequestView`（ツール詳細＋Allow/Deny） |
| null      | ≠ null        | `TimeoutView`（赤タイトル＋Dismiss） |
| null      | null          | `EmptyState`（"Waiting for Claude…"） |

### 6.3 `RequestView`

- タイトル「Permission requested」（selection 外）。
- `SelectionContainer` の中に:
  - `LabeledLine`: Tool / cwd / Agent (`subagent_type` for Agent tool) / File（Edit/Write/Read/NotebookEdit はリポジトリ相対パス）
  - `ToolInputBlock`
- ボタン行（selection 外）: Deny（OutlinedButton）/ Allow（Button, primary）
- Allow/Deny クリック時はポップオーバーを自動クローズ（`submit` の finally でクリア）。

### 6.4 `ToolInputBlock`

```
Edit       → File 行 + compactDiff(old→new) を DiffBlock、残りのフィールドを FieldList
Write      → File 行 + content を全行 INSERT 扱いで DiffBlock
NotebookEdit → File 行 + new_source を全行 INSERT で DiffBlock、残りを FieldList
それ以外   → FieldList のみ
```

### 6.5 `DiffBlock`

- 行番号カラム（DELETE=旧ファイル行番号、INSERT/EQUAL=新ファイル行番号）
- プレフィックス: `+` (INSERT, 緑背景) / `−` (DELETE, 赤背景) / ` ` (EQUAL, 無色)
- コンテキスト: 変更の前後 3 行だけ表示、それ以上離れた連続 EQUAL は `⋯ @@ −N +M @@` バナーで省略
- フォント: Monospace / bodySmall
- 横スクロール対応

### 6.6 `FieldList`

- Surface 内に key/value 行を縦積み、行間に `HorizontalDivider`（onSurfaceVariant × 0.25）
- ラベル: 10sp / uppercase / letter-spacing 1.2sp / SemiBold / muted 色
- 値: 14sp `bodyMedium`
- 複数行文字列 / JsonObject / JsonArray は CodeBlock（背景色・横スクロール・monospace）
- `consumedKeys(toolName)` で既に他所で表示済みのキーは除外

## 7. タイムアウト

| 値                                          | 所在 | デフォルト |
|---------------------------------------------|------|-----------|
| サーバー側 await 上限                       | `PermissionRequestHolder.DEFAULT_TIMEOUT_MS` | 580_000ms (9m40s) |
| hook script の curl `--max-time`            | `scripts/claude-menubar-hook.sh` | 600s |

- サーバーを 20s 早く切る設計: curl 側が socket を閉じる前にサーバーが 503 を返せる → mutex 解放が遅れない／IOException ログが出ない。
- タイムアウト時に実行されること:
  - `_pending=null`, `_popoverVisible=false`（ポップオーバー自動クローズ）
  - `_lastTimeout = request`（再展開で TimeoutView）
  - `_timeouts.tryEmit(request)` → `TrayIcon.displayMessage(…, INFO)` で macOS システム通知
  - トレイアイコン背景が amber → red
- セキュリティ: 自動 allow / deny にはせず、Claude Code の標準プロンプトに戻す（フェイルセーフ）。

## 8. ビルド・実行・テスト

```bash
# 実行
./gradlew :desktopApp:run

# 変更を即反映（Compose hot reload）
./gradlew :desktopApp:hotRun --auto

# ユニットテスト（LineDiff / compactDiff 16 ケース）
./gradlew :sharedUI:jvmTest

# フルビルド
./gradlew build

# ネイティブインストーラ
./gradlew :desktopApp:packageDistributionForCurrentOS
```

JVM: 17 以上（開発は Corretto 23 で確認）。Gradle 9、Compose Multiplatform 1.10.3、Kotlin 2.3.20。

## 9. 依存関係

- `compose.desktop.currentOs`（jvmMain）
- `kotlinx-coroutines-core` / `kotlinx-coroutines-swing`
- `kotlinx-serialization-json`
- `co.touchlab:kermit`（ログ）
- `net.java.dev.jna:jna 5.14.0`（AppKit 呼び出し）
- ランタイム: JDK 標準の `com.sun.net.httpserver.HttpServer`

## 10. 既知の制約・未解決タスク

`TODO.local.md`（git 非追跡）を参照。現状記録されている項目:
- トレイアイコン塗り領域 < クリック highlight 領域の乖離（AWT `TrayIcon` の限界、解決には `NSStatusItem` JNA 直叩きが必要）
