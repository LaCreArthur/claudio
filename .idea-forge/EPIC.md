# Idea Forge Backlog (v2 — Terminal Wrapper Architecture)

All old items (bridge elimination, frontend redesign, Kotlin migration, handler consolidation) are DONE — the entire old codebase was deleted and replaced with 695 LOC of Kotlin wrapping the CLI terminal.

## prompt_intercept — Native UI for CLI interactive prompts

Each one follows: detect output pattern → parse → show dialog → send answer back.

- [x] **AskUserQuestion** — ☐ pattern → radio button dialog → arrow keys + enter
- [ ] **Permission prompts** — `? Allow` / tool approval → approve/deny/always dialog
- [ ] **Plan approval** — plan mode review prompt → approve/reject dialog
- [ ] **Yes/No prompts** — simple Y/N → confirmation dialog
- [ ] **Tool confirmation** — dangerous operations → proceed/cancel
- [ ] **Cost warnings** — high-cost ops → native warning dialog
- [ ] **Error recovery** — CLI error prompts → retry/abort dialog

## ide_integration — Connect terminal to IDE features

- [x] **Send Selection** (Cmd+Alt+K) — editor selection → input bar
- [x] **Slash commands** — autocomplete popup for / commands
- [ ] **Clickable file paths** — detect paths in output → hyperlink → open in editor
- [ ] **File change notifications** — detect writes → editor notification / diff
- [ ] **Visual diffs** — detect edit tool output → open in DiffManager
- [ ] **Build error injection** — collect errors → prepend to prompt
- [ ] **Gutter action** — "Fix with Claude" on error lines
- [ ] **@file autocomplete** — project file index → insert in input bar
- [ ] **Terminal link handler** — register link patterns for file:line references

## session_mgmt — Multi-session and history

- [ ] **Multi-tab** — multiple Claude terminals in tool window tabs
- [ ] **History sidebar** — read ~/.claude/ JSON → list with titles + dates
- [ ] **Resume session** — select from history → send `/resume <id>`
- [ ] **Session export** — current session → markdown file
- [ ] **Prompt history** — up/down in input bar recalls previous prompts
- [ ] **New session button** — restart without closing tab

## context_injection — Get IDE state into prompts

- [ ] **Open files context** — list of editor tabs → inject as context
- [ ] **Git diff context** — uncommitted changes → inject
- [ ] **Build output context** — last build result → inject
- [ ] **Test output context** — last test run → inject
- [ ] **Error list context** — current file errors → inject

## polish — UX refinement (LATER — features first)

- [ ] **Toolbar** — new session, model display, settings
- [ ] **Theme-aware dialogs** — match IDE look and feel
- [ ] **Sound notification** — task complete while unfocused
- [ ] **Keyboard shortcuts** — Escape → editor, Cmd+Shift+C → focus Claude
- [ ] **Status bar widget** — session state in IDE status bar
- [ ] **Welcome screen** — first-launch guidance

## self_improvement — Meta

- [ ] **Pattern catalog** — document every discovered output pattern
- [ ] **Horizon scan** — check CLI changelog for new interactive patterns
- [ ] **Benchmark** — measure feature impact (dialog response time, user flow)
- [ ] **Skill amendment** — update skill based on cycle outcomes
