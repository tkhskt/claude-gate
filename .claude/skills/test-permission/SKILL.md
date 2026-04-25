---
name: test-permission
description: メニューバーアプリの権限ポップオーバー / 通知の動作検証用に、permission が要求されるダミーのツール呼び出し（Bash / Edit / Monitor / NotebookEdit / PowerShell / WebFetch / WebSearch / Write 等）を発行する。引数でツール種別やサイズのヒントを受け付ける。
argument-hint: "[Bash|Edit|Monitor|NotebookEdit|PowerShell|WebFetch|WebSearch|Write] [小|中|大|N行]"
---

# test-permission

このリポジトリ（claude-notification）の権限ハンドリング動作を確認するために、
permission ダイアログが立ち上がる無害なダミーのツール呼び出しを 1 回発行するスキル。

## 対応ツール

Claude Code の [tools-reference](https://code.claude.com/docs/en/tools-reference) で
`Permission Required: Yes` となっているツールを網羅する。引数で明示されたツールが
あればそれを使う。指定がなければ `Write` をデフォルトとする。

| ヒント | 実行内容 |
|--------|----------|
| `Write` | リポジトリ直下 `test-*.txt` への新規書き込み |
| `Edit` | 既存のダミーファイルへの 1 行置換。なければ先に Write してから Edit |
| `NotebookEdit` | リポジトリ直下に `test.ipynb` を置いてセルを編集 |
| `Bash` | 必ず permission ダイアログが出るコマンドを 1 回実行（例: `mkdir -p /tmp/test-permission-$(date +%s)` や `touch /tmp/test-permission-$(date +%s)`）。`echo`/`ls`/`date`/`pwd` 等の read-only 系は Claude Code に自動承認されてしまうため使わない |
| `PowerShell` | 必ず permission が要るコマンドを 1 回実行（例: `New-Item -ItemType File -Path "$env:TEMP\test-permission-$(Get-Random).txt"`）。`Get-Date`/`Get-Location` 等は自動承認される可能性があるため避ける。`CLAUDE_CODE_USE_POWERSHELL_TOOL=1` の環境でのみ動作 |
| `Monitor` | 副作用のある短命コマンド（`mkdir -p /tmp/test-permission-monitor-$(date +%s); echo done` 等）を Monitor で起動 |
| `WebFetch` | `https://example.com` を取得 |
| `WebSearch` | `claude code` 等の無害なクエリで検索 |
| `Skill` | （メタ）別の無害なスキルを Skill ツール経由で 1 回起動 |
| `ExitPlanMode` | プランモード中のみ意味を持つ。通常モードならこの指定は無視して `Write` にフォールバック |
| `MCP` / `mcp__<server>__<tool>` | 指定された MCP ツールを呼ぶ。引数で具体名を渡す |

## サイズヒント（Write / Edit / NotebookEdit のみ意味を持つ）

- `N行` / `N lines` → N 行のダミーテキスト
- `小` / `small` → 3〜5 行
- `中` / `medium` → 30〜50 行
- `大` / `巨大` / `large` / `huge` → 200 行以上
- 指定なし → 5 行程度

## 書き込み先 / 触ってよい範囲

- ファイル系ツール（Write / Edit / NotebookEdit）の対象は **リポジトリ直下の `test-*.{txt,ipynb}`** に限定。
- Bash / PowerShell / Monitor は **必ず permission を要求するコマンド** を選ぶ
  （`mkdir -p /tmp/test-permission-*`, `touch /tmp/test-permission-*` 等）。
  read-only 系（`echo`, `ls`, `date`, `pwd`, `Get-Date`）は Claude Code に
  自動承認され検証にならないため使わない。
- ただし破壊的・状態変更が大きいコマンド（`rm`, `mv`, `git` 書き込み系,
  パッケージインストール, ネットワーク送信）は禁止。書き込み先は `/tmp/` 配下に限定。
- WebFetch の URL は `https://example.com` 系の安全なエンドポイント固定。
- WebSearch のクエリは無害なキーワード（`claude code`, `kotlin multiplatform` 等）に限定。

## 振る舞い

- リクエストは **1 回だけ** 発行する。Deny されてもユーザーが明示的に
  「もう一度」と指示するまで再送しない。
- Deny / Timeout は期待動作の一つなので、結果をそのまま 1 行で報告する
  （例: 「メニューバーで拒否されました」「タイムアウトしました」）。
- Allow された場合も結果を 1 行で報告する。
- ファイル内容に意味は持たせない。行番号などプレースホルダで埋めて良い。

## やってはいけないこと

- 既存ソースコード（`sharedUI/`, `desktopApp/`, `scripts/`, `doc/` 等）への
  書き込み・編集
- 検証用ファイル以外の削除
- 同一リクエストの自動リトライ
- 副作用のあるコマンド（破壊的操作・ネットワーク送信・外部 API 書き込み）の実行
