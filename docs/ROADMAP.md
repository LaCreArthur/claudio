# Roadmap

**Updated:** 2026-03-28

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
- [x] @symbol autocomplete (PSI) - extend `@file` completion to resolve class/method names to their file:line locations (evidence: Cursor supports, strong IDE moat; effort: medium)
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

## Phase 11: IDE Intelligence

Exploit the IDE's live code model and project graph - things no terminal will ever see in real time.

- [x] @symbol PSI autocomplete - extend `@file` completion to resolve class/method names to their file:line via `JavaPsiFacade.findClass()` and `PsiShortNamesCache`; type `@MyClass` and get the exact source location injected (evidence: Phase 8 leftover; Cursor supports this; moat: very high - PSI class resolution is IDE-only; effort: medium)
- [x] Module dependency graph to Claude - "Send Module Graph" action injects the project's module dependency tree via `ModuleManager.modules` + `ModuleRootManager.getDependencies()`; lets Claude understand multi-module project structure instantly (evidence: issue #35814 explicitly mentions "project structure"; moat: high - ModuleManager API is IDE-only; effort: small)
- [x] IDE inspections on current file - "Run Inspections for Claude" runs the current file through `InspectionManager` (or uses existing `ProblemDescriptor` results from the daemon) and injects all warnings/errors with file:line as context; deeper than build errors - catches code smells, nullability, unused code (evidence: issue #35814 explicitly mentions "inspections"; moat: very high - InspectionManager is IDE-only; effort: medium)
- [x] Recent editor files context - "Send Recent Files to Claude" injects the last N files opened via `EditorHistoryManager.fileList`; useful as a "here's what I've been working on" context burst (evidence: EditorHistoryManager is IDE-only; natural context for architecture questions; moat: medium-high; effort: small)
- [x] Gradle/Maven task list injection - "Send Build Tasks to Claude" injects all available Gradle/Maven tasks via `ExternalSystemUtil` / `MavenProjectsManager`; lets Claude know exactly what build targets exist before giving run advice (evidence: issue #35814 "project structure"; moat: medium-high - ExternalSystemUtil is IDE-only; effort: small-medium)

## Phase 12: Code Archaeology

The IDE knows the full history and shape of your code - who changed what, what implements what, where everything is called from. Ship the features that turn that knowledge into instant Claude context, no copy-paste required.

- [x] VCS selection history - right-click any selection in the editor → "Send History to Claude" injects the git log + diffs for the selected line range via `VcsHistoryProvider` and `git log -L`; answers "when did this break and who touched it" without leaving the IDE (evidence: natural extension of shipped git blame; JetBrains VCS API covers all VCS not just git; moat: high; effort: small)
- [x] Find Usages to Claude - after running Find Usages, a "Send Usages to Claude" gutter/context action injects all found usage sites as file:line references; lets Claude understand the full call graph for a symbol before suggesting a refactor (evidence: Cursor has "find all references" in chat context; IntelliJ FindUsagesManager is IDE-only AST-level; moat: very high; effort: medium)
- [x] Test run history - "Send Test History to Claude" injects the last N test runs from `TestHistoryManager` with pass/fail counts, durations, and failure messages; natural trigger for "why are these tests flaky" or "write tests to cover these failures" (evidence: TestHistoryManager is IDE-only; no terminal equivalent for structured test result history; moat: high; effort: small)
- [x] Quick Documentation injection - when the Quick Documentation popup is open (F1 / hover), a "Send to Claude" button injects the rendered KDoc/Javadoc for the symbol via `DocumentationManager.getProviders()`; closes the gap vs Copilot Chat's "explain this API" feature (evidence: Copilot Chat has inline doc explanation; DocumentationManager is IDE-only; moat: high; effort: small)
- [x] Class/interface hierarchy to Claude - "Send Hierarchy to Claude" on any class or interface injects the full implementation tree via `ClassInheritorsSearch` / `InheritorsSearch`; lets Claude see all implementors before suggesting interface changes (evidence: Cursor shows class hierarchy in context; PSI InheritorsSearch is IDE-only; moat: very high; effort: medium)
- [x] Search Everywhere integration - register a Claudio contributor in Shift+Shift that turns any typed query into a prompt sent to the active Claude session; surfaces Claudio as a first-class IDE citizen alongside files, actions, and symbols (evidence: JetBrains SearchEverywhereContributor API; Copilot does this in VS Code Ctrl+I; moat: high - distribution + discoverability; effort: medium)

## Phase 13: Living Context

The IDE has a real-time picture of your session that no terminal can reconstruct: what breakpoints are set, what you wrote in scratchpads, what the last process printed, what the IDE itself just warned you about. Ship the features that turn the current moment into instant Claude context.

- [x] Active breakpoints to Claude - "Send Breakpoints to Claude" injects all active breakpoints (file, line, condition, enabled state) via `XBreakpointManager`; gives Claude the full picture of what you're watching before asking "why does this crash here" (evidence: natural complement to shipped debugger context; XBreakpointManager is IDE-only; moat: very high; effort: small)
- [x] Call hierarchy to Claude - right-click any method → "Send Call Hierarchy to Claude" injects all callers recursively via `CallHierarchyBrowser` / `PsiElement.callHierarchy`; answers "what breaks if I change this signature" before refactoring (evidence: complements Phase 12 Find Usages - usages show where, call hierarchy shows who calls in what order; PSI-level, no terminal equivalent; moat: very high; effort: medium)
- [x] Scratch file to Claude - a "Send Scratch to Claude" action in the Scratches tool window injects the full content of the selected scratch file via `ScratchFileService.getScratchRootType()`; scratch files are where developers prototype ideas and write ephemeral notes, making them natural Claude context (evidence: ScratchFileService is IDE-only; no terminal equivalent for IDE scratch concept; moat: high; effort: small)
- [x] IDE notification log to Claude - "Send Notification Log to Claude" injects the last 15 IDE notifications (errors, warnings, info) captured via message bus listener; answers "why did that just happen" for cryptic IDE warnings, Gradle sync failures, and plugin alerts without any copy-paste
- [x] Last run output to Claude - "Send Last Run Output to Claude" captures the most recent process output from `ExecutionManager` / `RunContentManager` without requiring a failure; lets Claude see what the app printed before the user even asks a question (evidence: complements Phase 7 failure injection - that requires failure, this works on success too; RunContentManager is IDE-only; moat: high; effort: medium)
- [x] Live templates to Claude - "Send Live Templates to Claude" injects the user's custom live template snippets via `TemplateManager.allTemplates`; lets Claude understand which shortcuts the developer already has so it can reference them in suggestions instead of writing boilerplate (evidence: TemplateManager is IDE-only; Copilot has no awareness of user-defined snippets; moat: high; effort: small)

## Principles

- **Lean into the IDE.** Build what standalone tools can't.
- **Minimize code.** The terminal does the heavy lifting.
- **Kotlin + Swing only.** No new languages, no npm, no extra runtimes.
- **Ship incrementally.** Each phase is independently useful.
