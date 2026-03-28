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
- [ ] Token/cost display per session (parse CLI output)
- [ ] Live cost ticker in status bar while generating
- [ ] Per-project cost tracking over time

## Phase 5: Deep IDE Integration

Remaining moat features. Things no terminal or standalone app can touch.

- [x] Gutter action: "Fix with Claude" on error lines
- [x] Checkpoint list with diff preview (visual rewind)
- [x] File change notifications (detect writes - show diff in editor)
- [x] CLAUDE.md indicator (show active files, edit from plugin)
- [x] MCP server status panel (connected servers, tools, enable/disable)
- [ ] One-click MCP setup (browse + install servers)

## Phase 6: Polish

- [ ] Toolbar (new session, model selector, settings)
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
- [ ] Run/test failure injection (when a run configuration fails, offer one-click to send output + stack trace to Claude)
- [ ] TODO/FIXME scan (scan project TODOs via IDE index, inject as a batch into Claude's input)
- [ ] Inline diff preview before applying (when Claude writes a file, show native IntelliJ diff dialog before the write lands)

## Principles

- **Lean into the IDE.** Build what standalone tools can't.
- **Minimize code.** The terminal does the heavy lifting.
- **Kotlin + Swing only.** No new languages, no npm, no extra runtimes.
- **Ship incrementally.** Each phase is independently useful.
