# Claudio Development Log

## 2026-04-04 — E2E Test Infrastructure + Coverage Expansion (18 -> 21 green)

### Changes
- **ClaudioTestBase.kt** - Fixed tool window ID "Claude" -> "Claudio" (was NPE-ing all 18 tests)
- **IdeMcpServer.kt** - EDT-safe start(): skip waitForStart() on EDT, use manager.port directly
- **RealClaudeSmokeTest.kt** - Added T6: model switch to Sonnet (real CLI, verified via probe)
- **ClaudioTestService.kt** + impl - Added 3 methods: getChangedFilePaths, hasChangedFile, clearChangedFiles
- **RemoteClaudioTestService.kt** - @Remote stubs for changed-files observability
- **ClaudePanel.kt** - Wired changedFilesAccessor/clearer to test service
- **ClaudioHookTest.kt** - Added 2 synthetic tests: @Remote wiring + PreToolUse Write acceptance
- **CLAUDE.md** - Full rewrite of architecture tree (8 -> 60 files), test structure section, vision reference
- **scripts/vision-*.sh** - Local VLM vision assertion scripts for future visual QA
- **claudio-e2e-loop skill** - New autonomous E2E test improvement loop with state, logs, proposals

### Learnings (see LEARNINGS.md)
- Stale constants in test code compile but NPE at runtime - always verify against plugin.xml
- BuiltInServerManager.waitForStart() on EDT throws AssertionError (not Exception)
- Slash command responses arrive in ~500ms - don't clearHistory before waiting for them
- Single test --tests runs in 29s vs 3min full suite - always use during development
- Plugin is the SUT, not the CLI - synthetic hook injection tests plugin behavior without CLI dependency

### Context (not obvious)
- All 18 original tests were broken since the tool window rename - nobody noticed because CI doesn't run integration tests
- The BuiltInServerManager EDT issue was masked in production because ClaudePanel.init runs on EDT but the SEVERE log was swallowed
- T6 model switch test uses real Sonnet tokens - justified as the only test verifying model switching works
- Changed-files observability uses Array<String> not List<String> because @Remote only supports primitives/arrays
- UX issues captured in bugs/ directory: subscription popup overlay, search everywhere dialog in test IDE

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
