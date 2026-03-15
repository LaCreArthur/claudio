# Codebase Architecture Map

Context gathered for upstream feature porting. Updated 2026-03-15.

## Process Spawning & Lifecycle

### 6 Process Spawning Paths

| Path | File | Method | Command | ProcessManager? | Timeout |
|------|------|--------|---------|-----------------|---------|
| Main bridge | `ClaudeSDKBridge.java:273` | `sendMessageWithBridge()` | `node bridge.js` | Yes | None (blocks on stdout) |
| Slash commands | `SlashCommandClient.java:53` | `getSlashCommands()` | `node bridge.js claude getSlashCommands` | No (fixed channel) | 20s |
| MCP status | `McpStatusClient.java:53` | `getMcpServerStatus()` | `node bridge.js claude getMcpServerStatus` | No (fixed channel) | 30s |
| Sync query | `SyncQueryClient.java:63` | `executeQuerySync()` | `node simple-query.js` | No | Configurable |
| Rewind | `RewindOperations.java:59` | `rewindFiles()` | `node bridge.js claude rewindFiles` | No | 60s |
| Session msgs | `SessionOperations.java:51` | `getSessionMessages()` | `node bridge.js claude getSession` | No | **None** |

### Process Management

- **ProcessManager.java**: `ConcurrentHashMap<String, Process>` keyed by channelId. `interruptedChannels` Set tracks user-aborted channels.
- **PlatformUtils.terminateProcess()**: SIGTERM → 3s wait → SIGKILL. Windows: `taskkill /F /T`.
- **Shutdown hook**: `ClaudeSDKToolWindow.registerShutdownHook()` (line 173) — 3s executor timeout.
- **Window dispose**: `ClaudeChatWindow.dispose()` (line 819) — interrupt session + cleanupAllProcesses.

### Zombie Process Gaps

- `SessionOperations` — no ProcessManager registration, no timeout
- `RewindOperations` — no ProcessManager registration (has 60s timeout)
- `SyncQueryClient.executeQuerySync()` — no ProcessManager registration
- `SyncQueryClient.executeQueryStream()` — calls waitForProcessTermination but never registers

### No Heartbeat/Keepalive

No periodic health monitoring of bridge processes. Liveness detected only by:
- `process.isAlive()` checks (ad-hoc)
- Timeout deadlines in slash/MCP clients
- stdout EOF in main bridge

## Environment Variables

### Set by Java (EnvironmentConfigurator.java)

| Variable | Purpose |
|----------|---------|
| `PATH` | Adds Node.js dir + system paths |
| `HOME` | For SDK to find `~/.claude/` |
| `NODE_PATH` | `~/.claude-gui/dependencies/node_modules` + global npm |
| `CLAUDE_PERMISSION_DIR` | `<tmpdir>/claude-permission/` |
| `TMPDIR/TEMP/TMP` | `<tmpdir>/claude-agent-tmp/` |
| `IDEA_PROJECT_PATH` | User's working directory |
| `CLAUDE_USE_STDIN` | `"true"` for stdin-based input |

### Set by Node (bridge.js)

| Variable | Purpose |
|----------|---------|
| `ANTHROPIC_AUTH_TOKEN` | From settings.json env |
| `ANTHROPIC_API_KEY` | From settings.json env |
| `ANTHROPIC_BASE_URL` | Custom API endpoint |
| `CLAUDE_CODE_TMPDIR` | Working directory |

### NOT Set (gaps)

- No `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY`
- No `NODE_TLS_REJECT_UNAUTHORIZED` / `NODE_EXTRA_CA_CERTS`

## Session Management

### Session IDs

- **channelId**: Java-generated UUID per bridge process launch (`ClaudeSession.launchClaude()` line 195). Maps to OS process.
- **sessionId**: From Claude SDK. Received via `{ type: "session_id" }` event. Used for resume.
- **No epoch/generation tracking** — no mechanism to reject stale messages from old sessions.

### Session Lifecycle

1. **Create**: `ClaudeChatWindow.createNewSession()` (line 681) — interrupts old session, creates new ClaudeSession, re-determines CWD
2. **Load history**: `ClaudeChatWindow.loadHistorySession()` (line 554) — creates new ClaudeSession with persisted sessionId
3. **Dispose**: `ClaudeChatWindow.dispose()` (line 819) — interrupt + cleanup

### determineWorkingDirectory() — Two Copies

Exists in both `SessionHandler.java:239` and `ClaudeChatWindow.java:520` (identical logic):
1. `project.getBasePath()` → if null/missing, fall back to `user.home`
2. Check `PluginSettingsService.getCustomWorkingDirectory(projectPath)` — supports relative paths
3. Validate exists + is directory → return, else return raw projectPath

**CWD bug**: No validation that the path is within the project. External files (e.g. `~/.claude/plans/*.md`) as active editor could produce wrong CWD.

## Streaming

### No Turn IDs

Streaming uses index-based tracking, not turn IDs:
- **Java**: `ClaudeMessageHandler` has `currentAssistantMessage`, `isStreaming`, `textSegmentActive/thinkingSegmentActive` booleans
- **Java**: `StreamingMessageHandler` has `updateSequence` (monotonic), `STREAM_MESSAGE_UPDATE_INTERVAL_MS = 50`
- **React**: `useStreamingCallbacks.ts` has `streamingContentRef`, `streamingMessageIndexRef`, segment arrays indexed by phase

**Race condition**: No protection against Java sending a snapshot while streaming is active — could overwrite in-progress content.

## Model Selection Flow

```
React ModelSelect → sendBridgeEvent('set_model', id)
  → MessageDispatcher → SettingsHandler.handleSetModel()
    → resolveActualModelName() (checks env overrides)
    → HandlerContext.setCurrentModel() + SessionState.setModel()
    → window.onModelConfirmed() → React
```

### Model Defaults

All three locations default to `claude-sonnet-4-6`:
- `ChatInputBox.tsx:32`, `ButtonArea.tsx:10`
- `HandlerContext.java:26`
- `SessionState.java:23`

### Parameters Passed to ai-bridge

| Parameter | Source |
|-----------|--------|
| `message` | User input |
| `sessionId` | SessionState (empty string if new) |
| `cwd` | SessionState |
| `permissionMode` | SessionState |
| `model` | SessionState |
| `openedFiles` | EditorContextCollector |
| `agentPrompt` | Selected agent |
| `streaming` | PluginSettingsService |
| `attachments` | Base64-encoded array |

**Not passed**: `maxTokens`, `maxThinkingTokens`, `reasoningEffort` — none of these are configurable.

### SDK query() options (bridge.js lines 336-351)

```javascript
options = { cwd, permissionMode, model, maxTurns: 100,
  enableFileCheckpointing: true, includePartialMessages: true,
  additionalDirectories: [cwd], canUseTool, settingSources, systemPrompt, resume }
```

## File I/O Charset Issues

### Using UTF-8 (correct)

- `ClaudeSDKBridge.java` — bridge process I/O
- `DependencyManager.java` — all file ops
- `HtmlLoader.java` — resource loading
- `ClaudeHistoryReader.java` — session files
- `BridgeDirectoryResolver.java` — version files

### Using platform default (broken on non-UTF-8 systems)

- `PluginSettingsService.java:126,141` — plugin config.json
- `ClaudeSettingsManager.java:42,62,81` — Claude settings.json
- `AgentManager.java:37,55` — agent JSON
- `McpServerManager.java:56,174,243,302,322` — MCP server configs
- `FileExportHandler.java:87` — exported files

## Attachment System

**useAttachmentManagement.ts**: Handles paste, drop, and file input.
- Clipboard images: `item.type.startsWith('image/')` → base64 via FileReader
- Clipboard text: plain text insertion
- Clipboard files: `window.getClipboardFilePath()` (Java-injected)
- Supported image types: jpeg, png, gif, webp, svg+xml

Already handles image paste from clipboard at the React level. The gap for upstream's clipboard paste feature may be smaller than expected — need to verify if `window.getClipboardFilePath()` is implemented on the Java side.

## Token Usage Tracking

- **UsageTracker.java**: Extracts `input_tokens`, `cache_*_tokens`, `output_tokens` from last assistant message, computes % against model context limit, pushes via `window.onUsageUpdate(json)`
- **ClaudeNotifier/StatusBarWidget**: Shows token info in IDE status bar
- **SettingsHandler.getModelContextLimit()**: All models = 200K. Supports `[NNNk]`/`[NNNm]` suffix parsing.
- **No per-message display** in the chat UI itself.

## Timeout Config

`TimeoutConfig.java`:
- `QUICK_OPERATION_TIMEOUT` = 30s
- `MESSAGE_TIMEOUT` = 180s (unused)
- `LONG_OPERATION_TIMEOUT` = 600s (unused)
- bridge.js: 30s timeout for initial stdin
