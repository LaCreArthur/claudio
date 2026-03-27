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

## Phase 2: Interactive Prompts - IN PROGRESS

- [x] AskUserQuestion detection (native dialog, answer sent back)
- [x] Permission prompt detection (Allow/Deny dialog)
- [ ] Plan approval detection (plan mode - review/approve dialog)
- [ ] Tool confirmation patterns
- [ ] Free-text input for AskUserQuestion (not just multiple choice)

## Phase 3: Multi-Session Management

The #1 requested feature across every competitor. Multiple agents, one window.

- [ ] Multi-tab chat (each tab = independent claude instance)
- [ ] Tab naming and reordering
- [ ] Session history sidebar (read ~/.claude/ history, show with timestamps + preview)
- [ ] Resume previous sessions from history
- [ ] Session favorites / pinning
- [ ] Export session to markdown

## Phase 4: Agent Presets + Usage Analytics

Turn tabs into purpose-built agents. Show what they cost.

- [ ] Agent presets (named configs: system prompt, permission mode, working directory, model)
- [ ] One-click launch: "Backend Agent", "Review Agent", "Test Agent"
- [ ] Token/cost display per session (parse CLI output)
- [ ] Usage summary widget in panel header or status bar
- [ ] Per-project cost tracking over time

## Phase 5: Deep IDE Integration

Things only an IDE plugin can do. This is the moat.

- [ ] Inject build errors / lint warnings / test failures as context
- [ ] Gutter action: "Fix with Claude" on error lines
- [ ] `@file` autocomplete in input bar (project file index)
- [ ] File change notifications (detect writes - show diff in editor)
- [ ] Rewind detection + native UI
- [ ] MCP server status panel (connected servers, tools, enable/disable)

## Phase 6: Polish

- [ ] Toolbar (new session, model selector, settings)
- [ ] Theme-aware styling for dialogs
- [ ] Sound/notification on task completion (window unfocused)
- [ ] Keyboard shortcuts: Escape - return to editor, Cmd+Shift+C - focus Claude
- [ ] Status bar widget (session state, model, cost)

## Principles

- **Lean into the IDE.** Build what standalone tools can't.
- **Minimize code.** The terminal does the heavy lifting.
- **Kotlin + Swing only.** No new languages, no npm, no extra runtimes.
- **Ship incrementally.** Each phase is independently useful.
