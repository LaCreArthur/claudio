# Claudio Project Learnings

[2026-03-30] #intellij #api: Gson is bundled with IntelliJ Platform - don't write custom JSON parsers
[2026-03-30] #intellij #websocket: Use WebSocketHandshakeHandler + built-in server (port 63342), not raw ServerSocket
[2026-03-30] #intellij #diff: No native "proposed changes review" API exists - VCS gutter has rollback only
[2026-03-30] #intellij #vfs: After writing files externally, call vf.refresh(false, false) to update editor
[2026-03-30] #claude-cli #ide: CLI discovers IDEs via ~/.claude/ide/<port>.lock files, connects ws://127.0.0.1:<port>
[2026-03-30] #claude-cli #ide: Use --ide flag for auto-connect, lockfile needs ideName="JetBrains <IDE>"
[2026-03-30] #claude-cli #ide: CLI protocol version "2025-11-25" (N86), supported: 2025-11-25 through 2024-10-07
[2026-03-30] #claude-cli #ide: Compiled binary uses Bun runtime - requires Netty WebSocket (raw sockets fail silently)
[2026-03-30] #rider: Open project with: open -a Rider /path/to/project.sln (needs .sln, not directory)
[2026-03-30] #workflow: Check existing APIs before implementing ANYTHING - jar tf + javap takes 5 min, custom code takes hours
[2026-04-04] #testing #starter: Tool window ID in tests must match plugin.xml - stale constants compile but NPE at runtime
[2026-04-04] #testing #edt: BuiltInServerManager.waitForStart() asserts NOT on EDT - use manager.port directly on EDT
[2026-04-04] #testing #timing: Don't clearHistory before waiting for /model response - slash commands respond in ~500ms
[2026-04-04] #testing #gradle: Single test via --tests "ClassName.method name" runs in 29s vs 3m for full suite
[2026-04-04] #testing #remote: @Remote methods must use Array<String> not List<String> for Driver API compatibility
[2026-04-04] #testing #philosophy: Plugin is the SUT, not the CLI - use synthetic hook injection, assert plugin state
[2026-04-04] #testing #dialog: Dialog dismiss timeout 15s minimum (not 5s) - cross-process @Remote EDT operations are slow
[2026-04-04] #testing #dialog: Subscription trial popup blocks EDT - use retry pattern for dialog dismiss assertions
[2026-04-04] #testing #dialog: PermissionDialog.doCancelAction() sets choice=DENY, not null - Cancel = explicit deny
[2026-04-04] #testing #debug: On failure: read screenshot first, then stacktrace, then fix - never guess-and-retry
[2026-04-04] #testing #infra: Tier 0 21+ tests hits IDE shutdown timeout - verify failures individually before investigating
