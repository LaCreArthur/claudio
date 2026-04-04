# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: Claudio

JetBrains plugin that embeds the `claude` CLI in a TerminalView widget with native IDE UX layered on top. Full Sonnet 4.6 / Opus 4.6 access via Max/Pro subscription - no API key needed.

**Repo:** `LaCreArthur/claudio`

**Pure Kotlin + Swing. No Java, no TypeScript, no npm.**

## Architecture

All source: `src/main/kotlin/com/lacrearthur/claudio/`

### Core (terminal + hooks + control plane)
- `ClaudeToolWindowFactory.kt` - registers the "Claude" tool window
- `ClaudePanel.kt` - per-tab panel: terminal lifecycle, hook wiring, output listener, input bar
- `ClaudioTabbedPanel.kt` - multi-tab container (each tab = independent ClaudePanel/claude session)
- `HookServer.kt` - HTTP server routing PreToolUse/PermissionRequest/Notification from claude hooks
- `HookInstaller.kt` - writes claudio-hook.sh + registers in ~/.claude/settings.json
- `CliOutputParser.kt` - terminal text parser (fallback for AskUserQuestion `☐` pattern only)

### Dialogs (native IDE UI replacing terminal prompts)
- `PermissionDialog.kt` - tool approval (Allow Once / Always Allow / Deny)
- `AskUserQuestionDialog.kt` - multiple-choice from AskUserQuestion tool
- `FreeTextQuestionDialog.kt` - free-text input variant
- `PlanExitDialog.kt` - plan mode exit confirmation
- `PresetEditorDialog.kt` - prompt preset editor

### IDE context senders (Send*Action pattern - ~20 actions)
All follow the same pattern: gather IDE context, format as text, send to Claude input bar.
- `SendSelectionAction.kt` (Cmd+Alt+K), `SendCurrentFileAction.kt`, `SendDiffToClaudeAction.kt`
- `SendCoverageGapsAction.kt`, `SendTestHistoryAction.kt`, `SendLastRunOutputAction.kt`
- `SendBreakpointsAction.kt`, `SendDebuggerContextAction.kt`, `SendCallHierarchyAction.kt`
- `SendHierarchyAction.kt`, `SendUsagesAction.kt`, `SendParameterInfoAction.kt`
- Plus: BookmarksAction, LiveTemplatesAction, DocumentationAction, FoldingMapAction, TodosAction, ModuleGraphAction, RunConfigsAction, ScratchFileAction, NotificationLogAction, ProjectFileAction, RecentFilesAction, BuildTasksAction, ProjectHealthAction, BranchSnapshotAction, FileHistoryAction, VcsSelectionHistoryAction, InlayHintsAction, RunInspectionsAction

### IDE integration
- `IdeMcpServer.kt` - MCP server over WebSocket (ws://127.0.0.1:<port>/api/claudio/mcp), exposes IDE capabilities (diagnostics, showDiff) to Claude CLI
- `IdeWebSocketHandler.kt` - WebSocket transport for MCP
- `IdeLockfileManager.kt` - writes ~/.claude/ide/<port>.lock for CLI discovery
- `ChangedFilesPanel.kt` - collapsible panel showing files changed by Claude with click-to-diff
- `SessionLoader.kt` - reads ~/.claude/ session history for resume
- `ClaudePreset.kt` - saved prompt presets
- `ClaudePathFilter.kt` - clickable file paths in terminal output
- `ClaudioStatusBarWidget.kt` - status bar indicator
- `ClaudioSearchEverywhereContributor.kt` - search everywhere integration

### Completions
- `SlashCommandCompletion.kt` + `SlashCommandRenderer.kt` - slash command autocomplete
- `AtFileCompletion.kt` - @file autocomplete from project index

### Error gutter
- `FixWithClaudeMarkerProvider.kt` - gutter icon on errors
- `AskClaudeAboutErrorIntention.kt` - intention action
- `ExplainWithClaudeAction.kt` - explain selection
- `RunFailureListener.kt` - auto-detect test/build failures
- `FocusClaudiaAction.kt` - focus the Claude tool window

### Utilities
- `JsonUtils.kt` - minimal JSON helpers (no library dependency)

### Test infrastructure (ships with plugin, never called in production)
- `test/ClaudioTestService.kt` - interface exposing internal state to Driver API
- `test/ClaudioTestServiceImpl.kt` - project service impl, wiring hooks for observability

### How It Works

1. Tool window "Claude" embeds a `TerminalView` (2025.3 Reworked Terminal API) running `claude` interactively
2. Plugin launches `claude` with `CLAUDIO_HOOK_PORT=<port>` env var to scope hooks to this session
3. **Hooks (primary control plane):** Claude Code fires structured JSON events to `HookServer` via HTTP
   - `PreToolUse` - policy layer (allow/deny/ask)
   - `PermissionRequest` - native approval dialog driven by structured `tool_name`/`tool_input`
   - `Notification` - status signals (permission_prompt, elicitation_dialog)
4. **Parser (fallback):** `CliOutputParser` detects AskUserQuestion `☐` pattern for user questions (no hook equivalent yet)
5. Input bar (bottom) lets users compose prompts, Cmd+Enter to send
6. The CLI handles everything else: auth, streaming, tools, rendering, CLAUDE.md

### Key API (TerminalView, 2025.3)

```kotlin
// Create detached terminal (not in Terminal tool window)
val tab = TerminalToolWindowTabsManager.getInstance(project)
    .createTabBuilder()
    .shouldAddToToolWindow(false)  // @Internal but Agent Workbench uses it
    .deferSessionStartUntilUiShown(true)
    .createTab()

// Send text
tab.view.createSendTextBuilder().shouldExecute().send(text)

// Read output (THE BIG UNLOCK)
tab.view.outputModels.regular.addListener(disposable, listener)

// Session lifecycle
tab.view.sessionState  // StateFlow<NotStarted | Running | Terminated>
```

**Do NOT use `useBracketedPasteMode()`** -injects text but doesn't submit.

### VFS Change Detection (2025.3)

```kotlin
project.messageBus.connect(disposable).subscribe(
    VirtualFileManager.VFS_CHANGES,
    object : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            for (event in events) {
                if (event !is VFileContentChangeEvent && event !is VFileCreateEvent) continue
                // event.path is the absolute path
            }
        }
    }
)
```

Use `project.messageBus` (not app messageBus) to scope listener to the project. Pass a `Disposable` to `connect()` to auto-unsubscribe on disposal.

### Hook Control Plane (primary)

```
Claude CLI (PreToolUse/PermissionRequest/Notification)
    ↓ hook fires, stdin = structured JSON
claudio-hook.sh (checks CLAUDIO_HOOK_PORT)
    ↓ curl POST to plugin
HookServer (routes by hook_event_name)
    ↓ PermissionRequest: show native dialog
hookSpecificOutput JSON response
    ↓ CLI receives structured decision
```

Response format: always `hookSpecificOutput` envelope.
- PreToolUse: `permissionDecision` (allow/deny/ask)
- PermissionRequest: `decision.behavior` (allow/deny)

### Terminal Parser (fallback for AskUserQuestion)

```
Terminal output → CliOutputParser.feed(text)
    ↓ detects ☐ ... ──── pattern
AskUserQuestionDialog (native DialogWrapper)
    ↓ user picks option
Escape sequences sent back: \u001b[B (down) + \r (enter)
    ↓
CLI receives the answer
```

Parser is emergency fallback only. No hook equivalent for AskUserQuestion yet.

## Build Version Matrix

**Reference:** `.claude/references/gradle-plugin-versions.md` (fetched from JetBrains docs)

| IDE Target | Kotlin Plugin | Kotlin stdlib | Gradle plugin helper |
|---|---|---|---|
| 2025.3.x | `2.2.20` | bundled -do NOT add as dep | `intellijIdea("2025.3.1")` |
| 2025.2.x | `2.1.20` | bundled -do NOT add as dep | `intellijIdea("2025.2.x")` |
| 2025.1.x | `2.1.10` | bundled | `intellijIdea("2025.1.x")` |
| 2024.3.x | `2.0.21` | bundled | `intellijIdeaCommunity("2024.3.x")` |

**Adding platform modules (not plugins) to compile classpath:**
```groovy
// Run ./gradlew printBundledModules to list all available module names
dependencies {
    intellijPlatform {
        bundledModule('intellij.platform.coverage')       // CoverageDataManager
        bundledModule('intellij.platform.coverage.agent') // ProjectData / ClassData / LineData
    }
}
```
Use `./gradlew printBundledModules` to discover available module names. Platform modules live in `lib/modules/` and are NOT on the compile classpath by default.

**Hard stop - classes that are in `app.jar` only (NOT in any bundledModule):**
- `EditorHistoryManager` (`com.intellij.openapi.fileEditor.impl`) - use `FileEditorManager.allEditors` instead for open-file context; `EditorHistoryManager` is not reachable via `bundledModule`

**Rules:**
- `intellijIdeaCommunity()` is deprecated for 2025.3+ -use `intellijIdea()`
- Always add `bundledPlugin("org.jetbrains.plugins.terminal")` for TerminalView API
- Never add `kotlin-stdlib` or `kotlinx-coroutines-core` as compile deps -both are bundled. Adding either causes loader constraint violations at runtime (two classloaders loading the same class)

## Commands

```bash
# Build
./gradlew clean buildPlugin      # Build plugin ZIP (build/distributions/)
./gradlew compileKotlin          # Quick compile check
./gradlew clean runIde           # Debug in sandbox IDEA

# Unit tests (no IDE, fast)
./gradlew test                   # HookScriptSubprocessTest, BuildVerificationTest, PlaceholderTest

# Integration tests (launches real IDEA 2025.3 via Starter + Driver)
./gradlew buildPlugin && ./gradlew integrationTest   # Tier 0: synthetic (no Claude CLI needed)
./gradlew buildPlugin && ./gradlew realE2ETest        # Tier 1: real Claude CLI (requires auth)

# Install in Rider
# Note: JAVA_HOME must be set - Java 21 is installed but not linked system-wide
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
./gradlew clean buildPlugin && \
rm -rf "$HOME/Library/Application Support/JetBrains/Rider2026.1/plugins/claudio" && \
unzip -oq build/distributions/claudio-*.zip \
  -d "$HOME/Library/Application Support/JetBrains/Rider2026.1/plugins/" && \
pkill -f Rider; sleep 5 && open -a Rider
```

**Important:** `buildPlugin` must run before integration tests - they install the ZIP into the test IDE.

## Key Decisions

- **Embedded terminal, not custom renderer**: The CLI handles all rendering (ANSI, markdown, tool cards). We add UX on top.
- **CLI subprocess, not direct SDK**: OAuth + SDK = Haiku only. CLI = all Max models.
- **Hooks as control plane, not terminal parsing**: Structured JSON events from CC hooks drive permissions and state. Parser is fallback only.
- **Pure Kotlin + Swing.** Gradle is the only build system.
- **Native dialogs over terminal prompts**: PermissionRequest hook shows IntelliJ DialogWrapper, returns structured decision. Terminal never shows the prompt.

## Test Structure

```
src/test/                                        # Unit tests (./gradlew test)
├── kotlin/.../HookScriptSubprocessTest.kt       # Hook script forwards JSON, silent when no port
├── kotlin/.../e2e/BuildVerificationTest.kt      # Plugin ZIP structure validation
└── java/.../PlaceholderTest.java                # Infrastructure smoke

src/integrationTest/                             # Integration tests (./gradlew integrationTest | realE2ETest)
├── kotlin/.../ClaudioTestBase.kt                # Base: Starter + Driver setup, IDE boot, trust prompt handling
├── kotlin/.../RemoteClaudioTestService.kt       # @Remote stub (16 methods) bridging test -> plugin process
├── kotlin/.../ClaudioHookTest.kt                # Tier 0 (11 tests): hook routing, permission dialog, parser, malformed JSON
├── kotlin/.../RealClaudeSmokeTest.kt            # Tier 1 @Tag("realE2E") (7 tests): real CLI round-trips
└── testData/test-project/                       # Minimal project for IDEA to open
```

**Tier 0** (`integrationTest`): Launches IDEA, injects synthetic hook events via HTTP, asserts on plugin state. No Claude CLI needed.

**Tier 1** (`realE2ETest`): Same IDE setup + real Claude CLI. Sends actual prompts, waits for responses, tests permission flows end-to-end. Requires `claude` CLI installed and authenticated. Uses Haiku by default for cost.

**Test service pattern:** `ClaudioTestServiceImpl` is a project service that ships with the plugin. `ClaudePanel` wires callbacks into it (hook port, transcript, session state, dialog refs). Tests call it via `RemoteClaudioTestService` (@Remote stub) across the Driver process boundary.

## Integration Tests (JetBrains Starter + Driver)

**Docs:** `.claude/references/jb-integration-tests-*.md` (fetched from official docs)

### Documented contracts (do not guess these)
- `runIdeWithDriver()` starts the IDE process
- `useDriverAndCloseIde { }` is where IDE interactions happen - the block DOES execute
- `@Remote("fqn", plugin = "com.lacrearthur.claudio")` - `plugin=` is REQUIRED
- Remote method types: primitives, String, arrays/lists of those, other `@Remote` refs only. No suspend, no complex objects.
- IDE version must be pinned explicitly - Starter downloads latest EAP by default
- Debug UI: `http://localhost:63343/api/remote-driver/` - inspect live component tree during a paused test

### Hard stops (burned by these, never again)
- Never glob `~/.gradle/caches` for IDE artifacts - use Starter's built-in download or `withVersion(...)`
- Never synthesize `.app` bundles with symlinks - macOS signing rejects them silently (process dies before logs)
- Never use `ExistingIdeInstaller` with extracted `Contents/` directories
- Never mix 3 problem classes in one debug pass: (1) JetBrains contract, (2) macOS signing, (3) plugin logic
- Never chase "does the lambda run?" for more than one proof cycle - a sentinel `assertTrue(blockExecuted)` is definitive

### VCS annotation API (FileAnnotation)
`FileAnnotation` only exposes:
- `getLineRevisionNumber(lineIndex: Int): VcsRevisionNumber?`
- `getToolTip(lineIndex: Int): String?`
There is no `getLineAuthor()` or `getLineDate()`. Extract author/date from tooltip string if needed.
Always call `annotation.dispose()` after reading (FileAnnotation implements Disposable).

### Bad patterns (wasted cycles on these)
- "53s for 4 tests = fake run" - WRONG. Starter uses warm IDE cache after first download. Speed is not evidence.
- Generating theories from partial evidence before reading the docs for that API - always doc first.

### IDE version alignment (current gap)
- Plugin targets `sinceBuild = '253'` (2025.3)
- Starter currently downloads IU 261.22158.121 (2026.1 EAP) - version mismatch
- Fix: use `withVersion("2025.3")` or equivalent in `IdeProductProvider.IU` call

## Development Rules

**Test-first mandate:** Before adding any new feature, all existing E2E tests must pass. `/claudio` runs tests before features - this is the law.

**No auto-deploy:** Never auto-install in Rider or auto-push/tag as part of a development cycle. Build and commit only. Deploy and release are explicit user actions.

**Real E2E = only proof:** Build passing + smoke test passing is not enough. Real E2E tests against the live claude CLI are the only authoritative proof. Synthetic tests cover wiring; real tests cover behavior.

## Local Vision (E2E visual assertions)

**Model:** Qwen3-VL-32B-Instruct-5bit (MLX) via LM Studio at `http://localhost:1234/v1`
**CLI:** `llm` (pipx) + `llm-lmstudio` plugin

```bash
# Describe a screenshot
./scripts/vision-describe.sh /tmp/screenshot.png "What dialogs are visible?"

# Assert a visual condition (exit 0=YES, 1=NO, 2=error)
./scripts/vision-assert.sh /tmp/screenshot.png "Is there a permission dialog visible?"
```

Use state assertions (`svc.getActiveDialogType()`) first. Use vision only for what state can't verify: button labels, visual layout, ANSI-rendered content, icons/colors.

## Code Style

- Kotlin only (no Java)
- English comments and strings
- Minimize code -the terminal does the heavy lifting
- Each new feature = detect output pattern + show native UI + send response

## Release Checklist

1. Update version in `build.gradle`
2. Update `CHANGELOG.md` with release notes (format: `##### **vX.Y.Z** (YYYY-MM-DD)`)
3. Commit: `chore: Bump version to X.Y.Z`
4. Tag: `git tag vX.Y.Z`
5. Push: `git push && git push --tags`
6. CI builds and publishes to JetBrains Marketplace on version tags
