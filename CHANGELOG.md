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
