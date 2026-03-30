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
