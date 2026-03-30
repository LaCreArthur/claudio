# Claudio Development Log

## 2026-03-30 14:00-17:30 — IDE MCP Server + Changed Files Panel

### Changes
- **IdeMcpServer.kt** - MCP JSON-RPC dispatcher on IntelliJ's built-in Netty server (port 63342)
- **IdeWebSocketHandler.kt** - WebSocket handler registered as httpRequestHandler extension
- **IdeLockfileManager.kt** - Writes ~/.claude/ide/<port>.lock for CLI auto-discovery
- **IdeMcpServer tools** - getDiagnostics, getSelection, getOpenFiles, showDiff
- **HookServer.kt** - PreToolUse snapshots file content before Edit/Write; VFS listener tracks changes
- **ChangedFilesPanel.kt** - Collapsible panel showing changed files with Keep/Undo, click-to-navigate
- **ClaudePanel.kt** - Polished input bar (rounded card, icon buttons, toolbar), wired MCP + panel
- **JsonUtils.kt** - Added buildJsonObject, buildJsonArray, extractInt (should migrate to Gson)

### Learnings (see LEARNINGS.md)
- Raw WebSocket server failed silently with Bun runtime; IntelliJ's Netty server worked immediately
- JsonUtils reimplements Gson which is already bundled and used by HookInstaller
- No native IntelliJ API for inline diff review with Keep/Undo buttons (Copilot/CC plugins build custom)
- ACP (Agent Client Protocol) is emerging standard but Claude Code doesn't support it natively yet

### Context (not obvious)
- The /ide lockfile protocol is undocumented — reverse-engineered from cli.js v2.1.87
- `CLAUDE_CODE_SSE_PORT` env var validates lockfile but doesn't control transport type
- The CLI detects JetBrains terminal via `TERMINAL_EMULATOR=JetBrains-JediTerm` env var
- Plugin compatibility range: sinceBuild=253 untilBuild=263.* (2025.3 through 2026.3)
- Tested on Rider 2025.3 and 2026.1 - no code changes needed for upgrade
