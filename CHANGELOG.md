##### **v0.7.6** (2026-03-28)

**Polish**

- Task completion notification - when Claude finishes generating and the Claudio panel is not visible, an IDE balloon notification pops up with the tab name and a "Focus" action to jump back instantly

##### **v0.7.5** (2026-03-28)

**Polish**

- Status bar widget - "⚡ Ready" / "⚡ Generating..." appears in the IDE status bar; updates every 500ms; click it to focus the Claudio tool window

##### **v0.7.4** (2026-03-28)

**Polish**

- Keyboard shortcut: Cmd+Shift+C (macOS) / Ctrl+Shift+C (Windows/Linux) activates and focuses the Claudio tool window from anywhere in the IDE; also available via Tools menu

##### **v0.7.3** (2026-03-28)

**Deep IDE Integration**

- MCP server status panel - a "MCP Servers" panel in the sidebar lists every server configured in `~/.claude/settings.json`; double-click any entry to open settings.json in the editor

##### **v0.7.2** (2026-03-28)

**Deep IDE Integration**

- CLAUDE.md indicator - the History sidebar now shows all active CLAUDE.md files at the top (project root, parent directories, global ~/.claude/CLAUDE.md); click any entry to open it in the editor

##### **v0.7.1** (2026-03-28)

**Deep IDE Integration**

- File change notifications - a "Changed Files" panel in the sidebar lists every file modified during the current session; double-click any entry to open the file in the editor

##### **v0.7.0** (2026-03-28)

**Deep IDE Integration**

- Checkpoint list - recent git commits appear in the sidebar below History; double-click any entry to preview the full diff inline

##### **v0.6.9** (2026-03-28)

**Deep IDE Integration**

- "Fix with Claude" gutter action - a lightning bolt icon appears in the editor gutter on error and warning lines; click it to send the error to Claudio's input bar instantly

##### **v0.6.8** (2026-03-28)

**Agent Presets**

- Sticky model per preset - assign a Claude model (Opus, Sonnet, Haiku, or Default) to any custom preset; the model is passed as `--model` when launching the session

##### **v0.6.7** (2026-03-28)

**Agent Presets**

- Preset editor - click "Edit presets..." in the ⚡ menu to create, edit, and delete custom agent presets; custom presets are saved to `~/.claudio/presets.json`

##### **v0.6.6** (2026-03-28)

**Agent Presets**

- Agent preset launcher - click ⚡ near the tab strip to open a new session pre-loaded with a named agent (Backend Agent, Review Agent, Test Agent); pre-fills the input bar with the agent's system prompt

##### **v0.6.5** (2026-03-28)

**Multi-Session**

- Resume session - double-click any entry in the History sidebar to resume that session via `claude --resume <id>`

##### **v0.6.4** (2026-03-28)

**Multi-Session**

- Session history sidebar - 220px west panel inside the Claudio tool window showing past Claude Code sessions from `~/.claude/projects/`; each entry shows timestamp and first user message preview

##### **v0.6.3** (2026-03-28)

**Multi-Session**

- Tab renaming - double-click any tab title to rename it inline; Enter or focus-out commits

##### **v0.6.2** (2026-03-28)

**Multi-Session**

- Multi-tab chat - each tab runs an independent claude process; click "+" to open a new session

##### **v0.6.1** (2026-03-28)

**IDE Context Integration**

- Build error injection - "⚠ Build" button in the input bar collects live IDE errors from open editors (filename:line: message) and appends them as context; shows error count on the button; shows "✓ Build" when clean

##### **v0.6.0** (2026-03-27)

**IDE Context Integration**

Features:
- `@file` autocomplete in the input bar - type `@` to browse and insert project files by relative path
- Alt+Enter on any error line - "Ask Claudio about this error" injects the error + file:line into the input bar
- Hook-based control plane - Claude Code hooks drive permissions and state instead of terminal parsing
- Hook installer - auto-writes `claudio-hook.sh` and registers it in `~/.claude/settings.json` on startup

##### **v0.5.0** (2026-03-26)

**Pure Kotlin TerminalView Plugin**

Features:
- Embedded TerminalView running `claude` CLI interactively - no API key required
- Input bar with Enter-to-send, Cmd+Enter for newlines
- Prompt history navigation (up/down buttons)
- Permission mode display + Shift+Tab cycling (lightning badge)
- Native permission dialogs (Allow / Deny) intercepting CLI prompts
- Clickable file paths in terminal output - `Update(path)` jumps to first modified line
- Multi-line paste via bracketed paste mode with event-driven submission
- Slash command autocomplete popup
- Send Selection action (Cmd+Alt+K) - selected editor text to input bar
