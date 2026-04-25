# claude-notification — 仕様

macOS メニューバー常駐アプリ。Claude Code の `PermissionRequest` フックから
tool 実行の許可リクエストを横取りし、ターミナルを開かずにポップオーバー UI
で Allow / Deny を操作可能にする。

---

## 1. 前提・スコープ

| 項目         | 値 |
|--------------|----|
| 対応 OS      | macOS（Retina 含む）。JDK 17+。非 Mac は tray / NSApp 周りは no-op |
| 対象フック   | `PermissionRequest`（主経路）／ `PostToolUse` / `UserPromptSubmit`（解決通知経路） |
| UI 技術      | Compose Multiplatform 1.10.x / Material3 / Kotlin 2.3.x |
| 非対応       | Windows, Linux（ビルド自体は通るがトレイ・アクティベーション周りは動作保証なし） |

## 2. アーキテクチャ

```
┌──────────────── Claude Code プロセス ────────────────┐
│  PermissionRequest 発火                               │
│    ↓                                                  │
│  scripts/claude-menubar-hook.sh (curl, no timeout)    │
└────────────────────────┬──────────────────────────────┘
                         │ POST JSON
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
| `sharedUI/.../permission/PermissionRequestHolder.kt`                              | 状態ホルダー。`submit / allow / deny / togglePopover / setPopoverVisible / dismissTimeout / resolveExternally` |
| `sharedUI/.../permission/HookResponse.kt`                                         | `hookSpecificOutput.decision.behavior` JSON 生成 |
| `sharedUI/.../popover/PopoverContent.kt`                                          | ポップオーバー本体 Composable |
| `sharedUI/.../popover/LineDiff.kt`                                                | LCS ベース行 diff + コンテキスト省略 (`compactDiff`) |
| `sharedUI/.../server/PermissionServer.kt`                                         | HTTP ハンドラ |
| `sharedUI/.../desktop/DesktopApp.kt`                                              | ApplicationScope 直下の全体合成、Tray・Window・Effect |
| `sharedUI/.../desktop/mac/MacApp.kt`                                              | AppKit main thread 経由の NSApp アクティベート／isActive |
| `sharedUI/.../desktop/awt/TrayNpeSuppression.kt`                                  | JDK の TrayIcon→LightweightDispatcher NPE を黙らせる EventQueue shim |
| `desktopApp/src/main/kotlin/main.kt`                                              | エントリポイント |
| `scripts/claude-menubar-hook.sh`                                                  | `PermissionRequest` hook 用 shell。curl で `/permission-request` に POST。失敗時 / 空応答時は exit 0 で Claude のフォールバックに委ねる |
| `scripts/claude-menubar-tool-resolved.sh`                                         | `PostToolUse` / `UserPromptSubmit` hook 用 shell。`/tool-resolved` に POST して popover 解決を通知。常に空 stdout で Claude のフローには影響しない |

## 4. Claude Code フック統合

### 4.1 受信エンドポイント

#### `POST /permission-request`（`PermissionRequest` フック由来）

- Body: Claude Code が hook に stdin で渡す JSON。`session_id` / `transcript_path` / `cwd` / `permission_mode` / `hook_event_name` / `tool_name` / `tool_input` / `permission_suggestions`。
- 通信: 単一 TCP 接続。リクエストごとに新規。

#### `POST /tool-resolved`（`PostToolUse` / `UserPromptSubmit` フック由来）

- Body: 各 hook event の JSON（同じ DTO で受ける、`session_id` / `tool_name` / `tool_input` / `hook_event_name` を利用）。
- 役割: 「Claude がこの tool を実行し終えた／ユーザーが次プロンプトに進んで pending を放棄した」というシグナルを受け取り、ポップオーバーが同セッションのリクエストを表示中なら `_pending` をクリアする。`_popoverVisible` は触らない。
- マッチング:
  - `PostToolUse` → `session_id` + `tool_name` + `tool_input` の完全一致
  - `UserPromptSubmit` → `session_id` 一致のみ。プロンプト内容は問わない（「ユーザーが次の入力に進んだ＝popover の判断をスキップして先に進んだ」という意味で同セッションの pending を無効化）
- decision マッピング:
  - `PostToolUse` → `ALLOW`
  - `UserPromptSubmit` → `DENY`
- 応答: HTTP 200 + 空 JSON `{}`（hook script はレスポンス body を無視）。
- なぜ UserPromptSubmit が必要か: 手動 terminal deny は **PostToolUse / PostToolUseFailure / PermissionDenied のいずれも発火しない**（[anthropics/claude-code#37769](https://github.com/anthropics/claude-code/issues/37769) 参照）。同 issue 由来の戦略を簡略化したもので、ユーザーが popover を放置して次のプロンプトを送ってきたなら、その popover はもう古いとみなして DENY 解決する。terminal deny ケースに加え、ユーザーが popover を見ずにそのまま会話を続けたケースも拾える。
- 試したが採用しなかった hook: `PreToolUse`（permission check より前なので `_pending` が空）／ `PermissionDenied`（auto-mode classifier 専用で手動 deny では発火しない）／ `Stop` / `PostToolUseFailure`（カバー範囲が `UserPromptSubmit` + 60s timeout と重複するうえ、実機で安定発火しなかった）。

### 4.2 出力（`PermissionRequest` 応答）

- 成功: HTTP 200 / `Content-Type: application/json`、body は
  ```json
  {"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}
  ```
  または `behavior: "deny"` + `message`。
- 外部解決: HTTP 200 / `Content-Type: application/json` で通常の hookSpecificOutput JSON を返す。`PostToolUse` または `UserPromptSubmit` 経由で `resolveExternally` が deferred を complete したケース。Claude は決定済みとみなして再発火しない。
- 400: JSON パース失敗時（ほぼ発生しない）。
- サーバー停止時: hook が `curl: (7) Failed to connect` → exit 0 → Claude Code のデフォルトプロンプトへフォールバック。

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
    ],
    "PostToolUse": [
      {
        "hooks": [
          { "type": "command", "command": "/absolute/path/to/scripts/claude-menubar-tool-resolved.sh" }
        ]
      }
    ],
    "UserPromptSubmit": [
      {
        "hooks": [
          { "type": "command", "command": "/absolute/path/to/scripts/claude-menubar-tool-resolved.sh" }
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

公開メソッド: `submit / allow / deny / togglePopover / setPopoverVisible / resolveExternally`。

`resolveExternally(other, override?)` は deferred を**完了** させる（cancel ではない）。マッチ条件と decision は event ごとに分岐:

| `hook_event_name`        | マッチ条件                                                         | decision |
|--------------------------|--------------------------------------------------------------------|----------|
| `PostToolUse`            | `session_id` + `tool_name` + `tool_input` 完全一致                 | `ALLOW`  |
| `UserPromptSubmit`       | `session_id` のみ（次プロンプト送信＝古い pending を放棄したとみなす） | `DENY`   |

`submit` 側ではこの完了が `EXTERNAL` 由来であると識別し、`_popoverVisible` には触れず `_pending` だけ finally でクリアする。これにより popover が開いていれば内容だけ「Waiting for Claude…」に切り替わり、ユーザーが popover を見ていない時は何も起きない。

deferred を complete してから `submit` が return するため、`PermissionRequest` の HTTP 応答は通常の `200 + hookSpecificOutput.decision.behavior` JSON となる。Claude は決定済みとして扱い再発火しない（cancel + 204 空 body だと Claude が「未決定」とみなして PermissionRequest を再投入する挙動を回避）。

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
  ├── deferred.await()  ← 時間制限なし
  │     ├── ユーザー Allow/Deny → deferred.complete(decision) → return
  │     └── 外部解決（PostToolUse / UserPromptSubmit 由来 resolveExternally）→ DecisionResult(EXTERNAL)
  ├── result.source == USER のときだけ _popoverVisible = false
  └── finally: _pending = null
  ↓
exchange に 200+JSON（USER / EXTERNAL いずれも）を書き戻す
```

### 5.3 並行リクエスト

- **同時複数到着**: `submitMutex` で UI 表示は直列化。各 submit 呼び出しは自分専用の `CompletableDeferred` を持つため、`allow/deny` は「現在表示中」のリクエストの deferred のみ完了させる。対応表:
  - A 到着 → 表示 → ユーザー判定 → A の exchange に応答 → mutex 解放
  - B 到着（A 処理中）→ mutex 待ち → A 完了後に UI 切替 → B 判定 → B の exchange に応答
- **執行スレッド**: `HttpServer` は `Executors.newCachedThreadPool()`。`PermissionRequest` ハンドラが最大 60s スレッドを占有しても新規リクエストには都度スレッドが供給される（fixed pool だと `/tool-resolved` などの軽量エンドポイントが starvation する）。
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
  - IDLE（pending=null）: 透明
  - AWAITING（pending≠null）: amber 500 (`#FFC107`)
- 切替: `holder.pending` を `LaunchedEffect` で監視 → `SwingUtilities.invokeLater { icon.image = … }`
- 既知の制約: 塗り領域 < クリック highlight 領域（AppKit 固有の padding）。詳細は `TODO.local.md`。

### 6.2 ポップオーバー状態切替

| `pending` | 表示                      |
|-----------|---------------------------|
| ≠ null    | `RequestView`（ツール詳細＋Allow/Deny） |
| null      | `EmptyState`（"Waiting for Claude…"） |

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

時間制約なし。`submit` は `deferred.await()` で待ち続け、以下のいずれかで解決:

- ユーザーが popover で Allow / Deny をクリック
- `PostToolUse` 経由 `resolveExternally(ALLOW)`
- `UserPromptSubmit` 経由 `resolveExternally(DENY)` — popover を放置して次プロンプトを送ったケース
- アプリ Quit / クラッシュ → curl が連動して切れ、Claude は hook 失敗扱いでフォールバック

`PermissionRequestHolder` には `withTimeout` を入れていないため、popover を考え込んでいる間に勝手にフォールバックする心配はない。`scripts/claude-menubar-hook.sh` も `curl --max-time` を指定せず、無期限待機。

セキュリティ: 自動 allow / deny は一切行わない。すべての解決はユーザー操作か外部 hook イベント由来。

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
