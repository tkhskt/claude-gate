# claude-gate — 仕様

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
┌──────── claude-gate プロセス (Compose Desktop) ────────┐
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
- **IPC**: Ktor server (CIO engine)。jpackage が同梱する jlink ランタイムには `jdk.httpserver` モジュールが含まれないため、JDK 標準の `com.sun.net.httpserver.HttpServer` から移行（バンドル時に `NoClassDefFoundError` で起動不能になる事象を回避）。
- **macOS 固有呼び出し**: JNA 経由で libobjc.A + libSystem（`dispatch_async_f`）。

## 3. コンポーネント

| ファイル                                                                           | 役割 |
|-----------------------------------------------------------------------------------|------|
| `sharedUI/.../permission/PermissionRequest.kt`                                    | `PermissionRequest` 等 hook input の `@Serializable` DTO |
| `sharedUI/.../permission/PermissionRequestHolder.kt`                              | 状態ホルダー。`submit / allow(id) / deny(id) / selectTab(id) / togglePopover / setPopoverVisible / resolveExternally`。複数 pending を List で保持（タブ表示） |
| `sharedUI/.../permission/HookResponse.kt`                                         | `hookSpecificOutput.decision.behavior` JSON 生成 |
| `sharedUI/.../popover/PopoverContent.kt`                                          | ポップオーバー本体 Composable |
| `sharedUI/.../popover/LineDiff.kt`                                                | LCS ベース行 diff + コンテキスト省略 (`compactDiff`) |
| `sharedUI/.../server/PermissionServer.kt`                                         | HTTP ハンドラ |
| `sharedUI/.../desktop/DesktopApp.kt`                                              | ApplicationScope 直下の全体合成、Tray・Window・Effect |
| `sharedUI/.../desktop/mac/MacApp.kt`                                              | AppKit main thread 経由の NSApp アクティベート／isActive。objc_msgSend / sel / cls / `runOnAppKitMain` を `internal` で公開 |
| `sharedUI/.../desktop/mac/MacStatusItem.kt`                                       | `NSStatusItem` を JNA 直叩き。`button.layer.backgroundColor` でメニューバーの highlight 領域全体を塗る。runtime obj-c class でクリック target/action を実装 |
| `sharedUI/.../desktop/awt/TrayNpeSuppression.kt`                                  | JDK の TrayIcon→LightweightDispatcher NPE を黙らせる EventQueue shim（AWT フォールバック時のみ意味あり） |
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

| Flow                                       | 意味 |
|--------------------------------------------|------|
| `pending: StateFlow<List<PendingRequest>>` | 同時並行で受け付けている全リクエスト。UI では 1 件ずつ表示し、複数のときは `ProgressFooter` の dots / chevron で切替 |
| `selectedId: StateFlow<String?>`           | 現在表示中のリクエスト ID。新着到着時は **常に** その ID に切替（hook 由来の最新パーミッションリクエストを即時表示。古い未解決タブは ProgressFooter の dots/chevron から到達可） |
| `popoverVisible: StateFlow<Boolean>`       | ポップオーバー表示中か。新着到着で必ず true に再セット |

`PendingRequest` は `id`（"req-N" 形式、内部 Mutex で生成）/ `request` / `fileLineOffset`（Edit 用、CodeDiffBlock の行番号を実ファイル基準に揃える 0-based オフセット）/ `deferred` を持つ。

公開メソッド: `submit / allow(id) / deny(id) / selectTab(id) / togglePopover / setPopoverVisible / resolveExternally`。

`submit` は **直列化しない**: 各呼び出しが自分専用の `CompletableDeferred` を持ち、複数の Ktor ハンドラから同時 await できる。pending リストへの追加・削除と ID 採番だけが排他制御の対象。

`resolveExternally(other)` は deferred を**完了** させる:

| `hook_event_name`        | マッチ条件                                                              | decision     | 効果                                   |
|--------------------------|-------------------------------------------------------------------------|--------------|----------------------------------------|
| `PostToolUse`            | `session_id` + `tool_name` + `tool_input` 完全一致（**一件だけ**）       | `ALLOW`      | 該当タブだけ消える                      |
| `UserPromptSubmit`       | `session_id` 一致（同セッションの**全 pending**）                         | `DENY`       | そのセッションの pending タブを一斉削除 |

`submit` 内で deferred 完了後の挙動:
- `_pending` から自分の entry を削除、`_selectedId` がそれを指していたなら先頭の残存タブにフォールバック
- `result.source == USER` かつ `_pending` が空のときだけ `_popoverVisible = false`（最後のユーザー判定でポップオーバーを閉じる）
- EXTERNAL 解決はポップオーバーを閉じない（ユーザーが見ているなら状態遷移を見せる）

deferred を complete してから `submit` が return するため、`PermissionRequest` の HTTP 応答は通常の `200 + hookSpecificOutput.decision.behavior` JSON となる。Claude は決定済みとして扱い再発火しない。

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

- **同時複数到着**: 並列に受け入れ、ユーザーは任意のリクエストで Allow / Deny を独立に選択可。直列化（旧 `submitMutex`）は撤去。表示は 1 件ずつ、`ProgressFooter` の dots / chevron で切替。
  - A 到着 → A 表示 + フッター "REQUEST 1 OF 1" / フッター自体は単数なら非表示 → B 到着 → "REQUEST 1 OF 2" のフッター出現（表示は A のまま）→ C 到着 → "REQUEST 1 OF 3"
  - ユーザーが A を Allow → A 消滅、表示は B（先頭の残存）に遷移
  - C を先に Deny → C 消滅、A/B はそのまま
  - すべて解決されると `_pending` 空 → 最後のユーザー判定で popover が閉じる
- **執行**: Ktor (CIO) のコルーチンディスパッチャで各ハンドラが並列に走る。各 `submit` は独立した `CompletableDeferred` を待つだけなので互いをブロックしない。
- **応答の取り違えは発生しない**: 各 deferred は ID で正確に呼び分けられる（`allow(id)` / `deny(id)`）。リクエストを跨いだ誤判定は構造的に起きない。

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
| サイズ（変化）     | 自動リサイズはアプリ起動後の **最初の `pending` 非空遷移 1 回だけ**。そのときの tool カテゴリが Edit/Write なら wide、それ以外は narrow を適用。以降は category 切替・queue ドレイン・新規リクエスト到来のいずれでも自動リサイズせず、ユーザーの手動リサイズが唯一の真実 |
| サイズ（最小）     | 420×420 dp（`window.minimumSize` を Compose `Window` の content スコープ内で `ComposeWindow` に直接設定）。Allow/Deny ボタン行のラベル折り返しを防ぐ下限 |
| 位置               | アンカー優先度: ①トレイクリック時の `MouseEvent.locationOnScreen` キャッシュ → ②`MacStatusItem.statusItemScreenCenterX()`（`[NSStatusItem.button.window frame]` を JNA 経由で読み取りキャッシュ、auto-open 時にメニューバーアイコン直下へ揃える）→ ③画面右上フォールバック |
| 装飾               | `undecorated = true, transparent = true, alwaysOnTop = true, focusable = true` |
| 表示制御           | `renderVisible` を `visibleTarget` から 1 tick 遅らせ、位置確定後に true（初回表示のチラつき抑止） |

### 5.6 macOS アクティベーション検知

`undecorated + transparent + alwaysOnTop` の AWT NSWindow は `canBecomeKeyWindow = NO` のため `WindowFocusListener` は不安定。代わりに 2 経路でクローズを検知:

- **イベント駆動（主経路）**: アプリ起動時に `MacApp.installResignActiveListener` が
  `NSApplicationDidResignActiveNotification` を購読する。NSApp が deactivate
  した瞬間に AppKit main thread でハンドラが発火し、`setPopoverVisible(false)`
  を呼ぶ。`alwaysOnTop` borderless ウィンドウで `[NSApp isActive]` ポーリングが
  取りこぼしがちなケース（runloop が tick するまで isActive が遷移しない、など）
  を即座に拾う。
- **ポーリング（フォールバック）**: 表示時に `MacApp.activateApp()` → 80ms ごとに
  `[NSApp isActive]` を再確認 → false なら `setPopoverVisible(false)`。
  `renderVisible = false` でコルーチンはキャンセル。通知が何らかの理由で発火
  しない場合の保険。

JNA 呼び出しは `dispatch_async_f` + `_dispatch_main_q` で AppKit main thread に乗せる（Swing EDT ≠ AppKit main on JDK 17+）。

### 5.7 Space 切替

canJoinAllSpaces / window level 操作を試みたが、Space 切替時の一時的 deactivation との両立が困難だったため**現状 Space 追従は非対応**（ポップオーバーは元の Space に残り、切替で見えなくなる）。

## 6. UI 表示仕様

### 6.1 トレイアイコン

- グリフ: 22×22 PNG（中央に小さな白い丸の輪郭＋ドット、それ以外は透過）。スロット幅は `[NSStatusItem setLength:22.0]` で固定。画像の高さはメニューバー厚と一致させ、`button.layer` の bounds = スロット全域となるようにしている。
- 塗りは `button.layer` と `button.window.contentView.layer` の両方に同じ背景色／cornerRadius／masksToBounds を流す。NSStatusBarButton が contentView 内のサブビューとして配置されている世代では button.layer だけだとスロット全域を覆えないため、外殻の contentView.layer にも同色を流して隙間を埋める（多くの世代では両者が同一インスタンスになり冪等）
- 背景色:
  - IDLE（pending=null）: 透明（layer.backgroundColor = nil）
  - AWAITING（pending≠null）: amber 500 (`#FFC107`)。`layer.cornerRadius = 6` + `masksToBounds = YES`
- 実装経路:
  - macOS: `MacStatusItem` が `[NSStatusBar systemStatusBar]` から `statusItemWithLength:NSVariableStatusItemLength` で `NSStatusItem` を作成、`button.wantsLayer = YES`。色は `layer.backgroundColor` に CGColor を流し込むことで、AppKit がそのアイテム用に確保している highlight 矩形全体を塗る（旧 AWT 実装で発生していた「塗り < highlight」の隙間が消える）
  - 非 macOS / 何らかの理由でネイティブ install が失敗した場合: AWT `TrayIcon` フォールバック（複合画像で amber 矩形を焼き込み）
- クリック: ネイティブ経路は runtime 生成 `NSObject` サブクラス (`CMNStatusItemClickTarget`) のインスタンスを `button.target` にして `action = onClick:` を設定。AppKit main thread から JVM 側の `togglePopover()` を発火
- アンカー位置: クリック発生時に `MouseInfo.getPointerInfo().location` をキャッシュ。NSStatusItem の target/action は MouseEvent を持たないので mouse 位置を擬似 anchor として利用
- 切替: `holder.pending` を `LaunchedEffect` で監視 → `MacStatusItem.setIconAndBackground(glyph, bg)`（AWT フォールバック時は従来通り `SwingUtilities.invokeLater { icon.image = … }`）

### 6.2 ポップオーバー状態切替

ポップオーバー全体は次の 3 段で構成される:

```
┌──────────────────────────────────────────┐
│ TopBar（高さ 48dp、下境界線）             │
│   "Claude Code · <project> · <session>"  │
│   右端: Quit（Power アイコン）            │
├──────────────────────────────────────────┤
│ Body（padding 24dp、空 or RequestView）  │
├──────────────────────────────────────────┤
│ ProgressFooter（pending.size > 1 のみ）  │
│   左: dots + "REQUEST X OF N"            │
│   右: prev/next chevron ボタン           │
└──────────────────────────────────────────┘
```

| `pending`        | Body                          |
|------------------|-------------------------------|
| 空               | `EmptyState`（"Waiting for Claude…"） |
| 1 件以上         | 選択中の `RequestView`         |

複数 pending の切替は **タブ列廃止**。代わりに ProgressFooter の dots / chevron で 1 件ずつ移動する（`holder.selectTab(id)`）。Allow / Deny は選択中タブの ID に対して `holder.allow(id)` / `holder.deny(id)` を発火する。

TopBar の `<project>` は `cwd` の最終セグメント、`<session>` は `session_id` の先頭 8 文字。どちらも該当値が無ければ省略。

### 6.3 `RequestView`

`Column { HeaderSection, PermissionCard, Spacer(weight=1f), Actions }`。

- **HeaderSection**: 40×40 の角丸枠（背景 `BrandSoft` `#33009688` / 枠線 `Brand` `#009688` 40% アルファ）に Figma 由来のシールドアイコン (`Res.drawable.ic_permission_shield`、`#00685E`) を配置。右側に「Permission Request」タイトル + `headerSubtitleFor(toolName)` のサブテキスト
- **PermissionCard**（`SelectionContainer` 内、選択コピー対象）:
  - メタデータ行: `TOOL` ラベル + `<TOOL_NAME>` バッジ（ティール薄塗り）
  - セカンダリ行: ファイルパス（Edit/Write/Read/NotebookEdit、リポジトリ相対）/ URL（WebFetch）/ クエリ（WebSearch）/ subagent 名（Agent）。該当が無ければ省略。Bash/PowerShell の command は Plain `CodeBlockSpec` 側で表示するためセカンダリ行には出さない
  - `CodeDiffBlock` または `FieldList` を本体として配置
- **Actions**（selection 外）: 左に「Deny」（淡い灰色 `OutlinedButton`、Deny アイコン）、右に「Allow」（ティール塗り `Button`、Check アイコン）。macOS HIG / システム permission prompt の「肯定アクションは右」慣習に合わせた配置

Allow / Deny クリック時はポップオーバーを自動クローズ（`submit` の finally でクリア）。

### 6.4 `ToolInputBlock`（旧称）→ `CodeDiffBlock` + `FieldList` の組合せ

`PermissionCard` 内で 1 件のリクエストに対し、ツールごとに以下を表示:

| Tool                 | CodeDiffBlock                                       | FieldList の残り |
|----------------------|-----------------------------------------------------|------------------|
| Edit                 | `compactDiff(old→new)`（タイトル「<filename> — Diff」、`+N -N` カウンタ） | 0 件             |
| Write                | content 全行を INSERT 扱い（タイトル「<filename> — Write」） | 0 件             |
| NotebookEdit         | new_source 全行を INSERT（タイトル「<filename> — Cell」） | 0 件             |
| Bash / PowerShell    | command を常に Plain ブロック表示（単行・複数行どちらも）     | command 以外     |
| WebFetch             | prompt が非空なら Plain ブロック                         | url / prompt 以外 |
| WebSearch            | なし                                                 | query 以外       |
| その他               | なし                                                 | toolInput 全体   |

セカンダリ行で 1 行で示せる情報（パス／URL／query／単行 command）は CodeDiffBlock を出さず、メタデータ行直下に折り畳む。

### 6.5 `CodeDiffBlock`

- 外枠: 黒背景 `#1E1E1E`、角丸 8dp
- 上部バー: `#2D2D2D`、`<filename> — Diff` などのタイトル、右端に `+N` (`#5EEAD4`) と `-N` (`#FCA5A5`) カウンタ
- Body（Diff の場合）:
  - 行番号カラム（DELETE=旧ファイル行番号、INSERT/EQUAL=新ファイル行番号）— **常に左端**。Edit ツールではサーバ側 (`PermissionServer.computeFileLineOffset`) が `file_path` を読んで `old_string` の位置を求め、その行オフセットを `PendingRequest.fileLineOffset` 経由で `lineDiff` に渡すので、ファイル中央の編集でも実際のファイル行番号で表示される（読み取り失敗・未マッチ時は 0 オフセットにフォールバック）
  - プレフィックス: `+` (INSERT, 緑系背景 `#4D134E4A`) / `-` (DELETE, 赤系背景 `#4D7F1D1D`) / ` ` (EQUAL, 透過)
  - 順序は **行番号 → +/- → 内容**（Figma の「+/- → 行番号 → 内容」とは別、現行 UX を維持）
  - コンテキスト: 変更の前後 3 行だけ表示、それ以上離れた連続 EQUAL は ` ⋯ @@ -N +M @@` バナーで省略
  - フォント: Monospace 12sp、横スクロール対応
- Body（Plain の場合）: 単純な monospace テキストブロック（横スクロール）

### 6.6 `FieldList`

- 白背景 + `#BFC9C7` 枠の `Surface`、角丸 6dp。key/value 行を縦積み、行間に `HorizontalDivider`
- ラベル: 10sp / uppercase / letter-spacing 1sp / SemiBold / `#3F4947`
- 値: 13sp プレーンテキスト
- 複数行文字列 / JsonObject / JsonArray は `CodeDiffBlock`（Plain モード）で表示
- `consumedKeys(toolName)` で既に他所（メタデータ行・diff・command/url 等）に表示済みのキーは除外

### 6.7 `ProgressFooter`（pending.size > 1 のみ表示）

- 上境界線 `#BFC9C7`、背景 `rgba(243,243,245,0.5)`、padding 24/16
- 左ブロック:
  - dots: 選択中は 12×6 のティール pill、その他は 6×6 の灰色 (`rgba(63,73,71,0.2)`) ドット。クリックで該当タブに移動
  - 「REQUEST X OF N」ラベル（11sp / Bold / uppercase / letter-spacing 1.1sp）
- 右ブロック:
  - 32×32 の白枠 chevron ボタン（prev / next）。先頭/末尾では半透明オーバーレイで disabled 表現
  - クリックで `holder.selectTab(prev/next id)`

## 7. タイムアウト

時間制約なし。`submit` は `deferred.await()` で待ち続け、以下のいずれかで解決:

- ユーザーが popover で Allow / Deny をクリック
- `PostToolUse` 経由 `resolveExternally(ALLOW)`
- `UserPromptSubmit` 経由 `resolveExternally(DENY)` — popover を放置して次プロンプトを送ったケース
- アプリ Quit / クラッシュ → curl が連動して切れ、Claude は hook 失敗扱いでフォールバック

`PermissionRequestHolder` には `withTimeout` を入れていないため、popover を考え込んでいる間に勝手にフォールバックする心配はない。`scripts/claude-menubar-hook.sh` も `curl --max-time` を指定せず、無期限待機。

セキュリティ: 自動 allow / deny は一切行わない。すべての解決はユーザー操作か外部 hook イベント由来。

## 7.5 macOS バンドル設定

`desktopApp/build.gradle.kts` の `nativeDistributions.macOS.infoPlist.extraKeysRawXml` で以下を Info.plist に注入:

| Key | 値 | 目的 |
|-----|-----|------|
| `LSUIElement` | `true` | Dock にアイコン非表示・⌘Tab のアプリスイッチャーにも出さない（メニューバー常駐アプリの定石） |

加えて `application.jvmArgs("-Dapple.awt.UIElement=true")` を併用。これがないと Java AWT 初期化時に `NSApplicationActivationPolicyRegular` で NSApplication を初期化してしまい、Info.plist の `LSUIElement` が打ち消され、jpackage バンドルで NSStatusItem が出ない／Dock アイコンが復活する事象が起きる。

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
- `io.ktor:ktor-server-core` / `io.ktor:ktor-server-cio`（HTTP サーバ・CIO エンジン）

## 10. 既知の制約・未解決タスク

`TODO.local.md`（git 非追跡）を参照。
