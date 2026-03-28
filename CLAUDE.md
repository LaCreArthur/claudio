# CLAUDE.md

## Project: Claudio

JetBrains plugin that embeds the `claude` CLI in a TerminalView widget with native IDE UX layered on top. Full Sonnet 4.6 / Opus 4.6 access via Max/Pro subscription - no API key needed.

**Repo:** `LaCreArthur/claudio`

## Architecture

```
src/main/kotlin/com/lacrearthur/claudio/
├── ClaudeToolWindowFactory.kt   # ClaudePanel: terminal + input bar + hook wiring
├── HookServer.kt                # HTTP server - routes PreToolUse/PermissionRequest/Notification
├── HookInstaller.kt             # Writes hook script + registers in ~/.claude/settings.json
├── CliOutputParser.kt           # Terminal text parser (fallback for AskUserQuestion only)
├── ClaudePathFilter.kt          # Clickable file paths in terminal output
├── SendSelectionAction.kt       # Cmd+Alt+K: editor selection to input bar
├── SlashCommandCompletion.kt    # Slash command autocomplete popup
├── SlashCommandRenderer.kt      # Popup cell renderer
src/main/resources/
├── META-INF/plugin.xml          # Plugin manifest
├── icons/                       # SVG/PNG icons
```

**Pure Kotlin + Swing. No Java, no TypeScript, no npm.**

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

**Rules:**
- `intellijIdeaCommunity()` is deprecated for 2025.3+ -use `intellijIdea()`
- Always add `bundledPlugin("org.jetbrains.plugins.terminal")` for TerminalView API
- Never add `kotlin-stdlib` or `kotlinx-coroutines-core` as compile deps -both are bundled. Adding either causes loader constraint violations at runtime (two classloaders loading the same class)

## Commands

```bash
./gradlew clean buildPlugin      # Build plugin ZIP (build/distributions/)
./gradlew compileKotlin          # Quick compile check
./gradlew clean runIde           # Debug in sandbox IDEA
```

### Install in Rider

```bash
./gradlew clean buildPlugin && \
rm -rf "$HOME/Library/Application Support/JetBrains/Rider2025.3/plugins/claudio" && \
unzip -oq build/distributions/claudio-*.zip \
  -d "$HOME/Library/Application Support/JetBrains/Rider2025.3/plugins/" && \
pkill -f Rider && sleep 2 && open -a Rider
```

## Key Decisions

- **Embedded terminal, not custom renderer**: The CLI handles all rendering (ANSI, markdown, tool cards). We add UX on top.
- **CLI subprocess, not direct SDK**: OAuth + SDK = Haiku only. CLI = all Max models.
- **Hooks as control plane, not terminal parsing**: Structured JSON events from CC hooks drive permissions and state. Parser is fallback only.
- **Pure Kotlin + Swing.** Gradle is the only build system.
- **Native dialogs over terminal prompts**: PermissionRequest hook shows IntelliJ DialogWrapper, returns structured decision. Terminal never shows the prompt.

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
