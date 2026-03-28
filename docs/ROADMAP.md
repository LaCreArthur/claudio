# Roadmap

**Updated:** 2026-03-27

## Vision

The only Claude Code GUI that lives inside your IDE. We don't compete with standalone apps (Claudia, claude-code-web) on remote access or orchestration frameworks. We win on deep IDE integration that external tools can't touch.

## Architecture

```
TerminalView (renders CLI)
  ↓ outputModels.regular.addListener()
CliOutputParser (detects patterns)
  ↓ callback
Native Swing UI (dialogs, popups)
  ↓ escape sequences / sendText
Terminal (sends answer to CLI)
```

Each feature = detect pattern + show native UI + send response.

## Phase 1: Core Terminal Wrapper - DONE

- [x] TerminalView embedding (detached from Terminal tool window)
- [x] Input bar with Enter-to-send, Cmd+Enter for newlines
- [x] Auto-restart on claude exit
- [x] Status indicator (Generating.../Ready)
- [x] Send Selection action (Cmd+Alt+K)
- [x] Slash command autocomplete popup (built-in + user skills)
- [x] Prompt history (up/down buttons)
- [x] Permission mode badge + Shift+Tab cycling
- [x] Clickable file paths - diffs jump to first modified line
- [x] Output listener wired via TerminalView API

## Phase 2: Interactive Prompts - DONE

- [x] AskUserQuestion detection (native dialog, answer sent back)
- [x] Permission prompt detection (Allow/Deny dialog)
- [x] Plan approval detection (plan mode - review/approve dialog)
- [x] Tool confirmation patterns (robust multi-pattern permission detection)
- [x] Free-text input for AskUserQuestion (not just multiple choice)

## Phase 3: IDE Context + Multi-Session

Pull the moat features forward. `@file` and error injection are what make this better than a terminal.

- [x] `@file` autocomplete in input bar (project file index)
- [x] Alt+Enter on error lines - "Ask Claudio about this error" (AskClaudeAboutErrorIntention)
- [x] Inject build errors / lint warnings / test failures as context (build button in input bar)
- [x] Multi-tab chat (each tab = independent claude instance)
- [x] Tab naming (double-click to rename inline)
- [x] Session history sidebar (read ~/.claude/ history, show with timestamps + preview)
- [x] Resume previous sessions from history

## Phase 4: Agent Presets + Usage Analytics

Turn tabs into purpose-built agents (Roo-killer). Show what they cost.

- [x] Agent presets (named configs: system prompt, permission mode, working directory, model)
- [x] One-click launch: "Backend Agent", "Review Agent", "Test Agent"
- [x] Sticky model per preset (Architect uses reasoning model, Code uses fast one)

### Agent presets - sub-items
- [x] Launch from preset: `ClaudePreset(name, systemPrompt)` data model + 3 built-in defaults (Backend/Review/Test) + "⚡ Preset" dropdown button + fills input bar and renames tab on open
- [x] Preset editor: create/edit/delete custom presets, persist to `~/.claudio/presets.json`
- [x] Sticky model: pass `--model <model>` to the claude process at tab launch time
- [x] Token/cost display per session (parse CLI output)
- [x] Live cost ticker in status bar while generating
- [x] Per-project cost tracking over time

## Phase 5: Deep IDE Integration

Remaining moat features. Things no terminal or standalone app can touch.

- [x] Gutter action: "Fix with Claude" on error lines
- [x] Checkpoint list with diff preview (visual rewind)
- [x] File change notifications (detect writes - show diff in editor)
- [x] CLAUDE.md indicator (show active files, edit from plugin)
- [x] MCP server status panel (connected servers, tools, enable/disable)
- [x] One-click MCP setup (browse + install servers)

## Phase 6: Polish

- [x] Toolbar (new session, model selector, settings)
- [x] Theme-aware styling for dialogs
- [x] Sound/notification on task completion (window unfocused)
- [x] Keyboard shortcuts: Escape - return to editor, Cmd+Shift+C - focus Claude
- [x] Status bar widget (session state, model, cost)
- [x] Session favorites / pinning
- [x] Export session to markdown

## Phase 7: IDE-Native Power Features

Deep hooks into IntelliJ's execution model and code model. Things no standalone Claude GUI can do.

- [x] Tab close button (× on each tab - terminates the claude subprocess cleanly)
- [x] "Explain this" right-click action (select any code → "Ask Claude to explain" → injects selection + file context via PSI)
- [x] Run/test failure injection (when a run configuration fails, offer one-click to send output + stack trace to Claude)
- [x] TODO/FIXME scan (scan project TODOs via IDE index, inject as a batch into Claude's input)
- [x] Inline diff preview before applying (when Claude writes a file, show native IntelliJ diff dialog before the write lands)

## Phase 8: Context Superpowers

The IDE knows things no terminal ever will: where the cursor is right now, what the debugger sees, who last touched that line. Ship the features that exploit this.

- [x] "Send Current File" action - one click/shortcut to inject `@<active-editor-file>` into input bar (evidence: ghostty-claude-intellij core feature, issue #40020; moat: uses IDE file tracking; effort: small)
- [x] Debugger context injection - when paused at a breakpoint, "Send to Claude" injects current stack frames + visible variables (evidence: Cursor has this, no JetBrains Claude plugin does; moat: very high - uses XDebugger API; effort: medium)
- [ ] @symbol autocomplete (PSI) - extend `@file` completion to resolve class/method names to their file:line locations (evidence: Cursor supports, strong IDE moat; effort: medium)
- [x] Git blame context - "Ask Claude to Explain" automatically includes git blame for selected lines (who/when/commit) (evidence: JetBrains VCS API, extends existing PSI action; effort: small)
- [x] Send to Claude from Project view - right-click any file in Project tree → "Send to Claude" inserts `@filepath` (evidence: ghostty plugin, complements Send Selection; effort: small)
- [x] Context window progress bar - show `1.2k/200k` token usage next to cost label using accumulated token count vs known model limits (evidence: issue #40018; uses already-tracked totalTokens; effort: small)

## Phase 9: Input Superpowers

The terminal can't paste images, handle drag-and-drop, or remember what you allowed last time. Ship the things that make the input bar genuinely better than a text field.

- [x] @symbol autocomplete - extend `@file` completion to show name-matching files (⊞ prefix) via FilenameIndex in addition to path matches (evidence: Cursor supports, strong IDE moat, already planned in Phase 8; effort: medium)
- [x] Image paste in input bar - Cmd+V with an image on the clipboard saves it to a temp file and injects the path into the prompt; lets users paste screenshots of errors/UI directly (evidence: issue #33005 anthropics/claude-code; moat: high - requires IDE clipboard API; effort: medium)
- [x] Drag-and-drop files to input bar - drag any file from the Project view into the input bar to insert `@filepath` as context (evidence: issue #35814 requests this as a "native experience" feature; moat: medium - requires JetBrains DnD API; effort: medium)
- [x] Persistent "Always Allow" per project - add a "Remember for this project" checkbox to permission dialogs; approved commands saved to `~/.claudio/allowlist/<project-hash>.json` and auto-allowed on future requests (evidence: issue #38795 - repeated approvals are top user pain; moat: high - our hook dialogs own the flow; effort: small)
- [x] Debugger variable snapshot - extend debugger context injection to include visible local variables from the XDebugger variable tree at the breakpoint (evidence: Cursor does this; moat: very high - XDebugger API only; effort: large)
- [x] /add-dir directory picker - a native "Add Directory" button in the toolbar opens a folder chooser and types `/add-dir <path>` into the input bar (evidence: issue #36123; moat: low-medium - removes CLI friction; effort: small)

## Phase 10: VCS + Code Intelligence

The IDE's code model and version control awareness are things no terminal will ever have. Ship the features that exploit them.

- [x] Send uncommitted diff to Claude - a "Send Diff" button (or right-click in the Changes panel) injects the full working tree diff via `GitChangeListManager`; lets Claude review pending changes without manual `git diff` copy-paste (evidence: issue #35814 native IDE integration; moat: high - VCS API works for all VCS, not just git; effort: small)
- [x] Library class / JAR source to Claude - right-click any external library class (e.g. in the Project view → External Libraries) → "Send to Claude" injects the decompiled source via PSI/`JavaDecompilerService`; closes the gap vs Gemini Code Assist (evidence: issue #29117 - Gemini can, Claude can't; moat: very high - PSI decompilation is IDE-only; effort: medium)
- [x] Editor bookmarks to Claude - "Send Bookmarks to Claude" action injects all bookmarked file:line locations as a numbered list; bookmarks represent the developer's focus points and annotated TODOs (evidence: BookmarkManager is IDE-only with no terminal equivalent; moat: high; effort: small)
- [x] Run configuration context - right-click any run configuration → "Send to Claude" injects the config name, type, and effective command line; lets Claude understand exactly how the project runs before giving advice (evidence: RunManager API is IDE-only; natural "explain how to run this" workflow; moat: high; effort: small)
- [x] Coverage gaps to Claude - after running with coverage, a "Send Uncovered Methods" action reads `CoverageDataManager` and injects methods with zero or partial line coverage; natural trigger for "write tests for these" requests (evidence: CoverageSuite is IDE-only, no terminal equivalent; moat: very high; effort: medium)

## Principles

- **Lean into the IDE.** Build what standalone tools can't.
- **Minimize code.** The terminal does the heavy lifting.
- **Kotlin + Swing only.** No new languages, no npm, no extra runtimes.
- **Ship incrementally.** Each phase is independently useful.
