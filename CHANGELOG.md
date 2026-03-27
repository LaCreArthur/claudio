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
