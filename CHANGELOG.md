##### **v0.8.40** (2026-03-28)

**Living Context**

- IDE notification log to Claude - Tools > Send Notification Log to Claude injects the last 15 IDE notifications (errors, warnings, and info) with their type and content; paste this into Claude to ask "what does this mean" for cryptic build tool or plugin warnings

##### **v0.8.39** (2026-03-28)

**Living Context**

- Scratch file to Claude - open any scratch file in the editor, then Tools > Send Scratch File to Claude injects its content into the input bar; scratch files are where developers think through problems - now Claude can see that thinking too

##### **v0.8.38** (2026-03-28)

**Living Context**

- Call hierarchy to Claude - place caret on any function or method and choose "Send Call Hierarchy to Claude"; injects all call sites (up to 50) with file:line and line text so Claude knows exactly what will break before you change the signature

##### **v0.8.37** (2026-03-28)

**Living Context**

- Active breakpoints to Claude - Tools > Send Breakpoints to Claude injects all XDebugger breakpoints with file:line, enabled state, and condition expressions; shows disabled breakpoints too so Claude has the full debug configuration picture

##### **v0.8.36** (2026-03-28)

**IDE Integration**

- Search Everywhere integration - press Shift+Shift and type any query to see "Ask Claude: <query>" in the results; selecting it populates the Claudio input bar instantly, making Claude a first-class citizen in the IDE's universal search alongside files, actions, and symbols

##### **v0.8.35** (2026-03-28)

**Code Archaeology**

- Class hierarchy to Claude - place caret on any class or interface and choose "Send Hierarchy to Claude"; injects all implementors and subclasses (up to 30) as a file:line list so Claude knows the full inheritance tree before suggesting interface changes

##### **v0.8.34** (2026-03-28)

**Code Archaeology**

- Find Usages to Claude - place the caret on any symbol in the editor and choose "Send Usages to Claude" (right-click menu or Tools menu); Claudio runs a project-scope reference search and injects up to 50 usage sites as file:line references with line text; ideal before asking Claude to help refactor an API

##### **v0.8.33** (2026-03-28)

**Code Archaeology**

- VCS selection history - select any code in the editor, right-click and choose "Send Selection History to Claude"; injects the git log -L output for the selected line range (up to 10 commits, 100 lines) so Claude can see exactly who changed these lines and when

##### **v0.8.32** (2026-03-28)

**Code Archaeology**

- Test run history to Claude - Tools > Send Test History to Claude injects the last 5 test runs (name, timestamp, pass/fail counts, duration, first failure message per run) from the project's .idea/testHistory/ directory; natural trigger for "why are these tests flaky" or "what broke"

##### **v0.8.31** (2026-03-28)

**Code Archaeology**

- Quick Documentation to Claude - right-click any symbol in the editor and choose "Send Documentation to Claude"; Claudio injects the KDoc/Javadoc for the symbol at the caret position stripped of HTML; works for all IDE-supported languages via DocumentationManager

##### **v0.8.30** (2026-03-28)

**IDE Intelligence**

- Gradle/Maven task list to Claude - Tools > Send Build Tasks to Claude injects all available Gradle tasks grouped by category (up to 60) into the input bar; lets Claude understand what build targets exist before giving run or test advice

##### **v0.8.29** (2026-03-28)

**IDE Intelligence**

- IDE inspections on current file - Tools > Run Inspections for Claude reads existing daemon analysis results (warnings + errors) from the current editor file and injects them as a `file:line: severity: message` list into the input bar; capped at 50; deeper than build errors - catches code smells, nullability issues, unused code

##### **v0.8.28** (2026-03-28)

**IDE Intelligence**

- Send open files to Claude - Tools > Send Recent Files to Claude injects all currently open project files as a numbered list into the input bar; useful as a "here's what I'm working on" context burst before architecture questions

##### **v0.8.27** (2026-03-28)

**IDE Intelligence**

- Module dependency graph to Claude - Tools > Send Module Graph to Claude injects the full project module dependency tree (up to 50 modules) as a formatted list; shows which modules depend on which so Claude understands multi-module project structure instantly

##### **v0.8.26** (2026-03-28)

**IDE Intelligence**

- @symbol PSI autocomplete - `@` completions that match by filename now include a precise `:line` pointing at the class declaration; type `@MyClass` and pick the `⊞` entry to inject `@src/MyClass.kt:12` so Claude jumps straight to the declaration; uses universal PSI tree walking so it works for Kotlin, Java, C#, and all other languages supported by the IDE

##### **v0.8.25** (2026-03-28)

**VCS + Code Intelligence**

- Coverage gaps to Claude - Tools > Send Coverage Gaps to Claude reads active coverage data via `CoverageDataManager` and injects all classes with less than 80% line coverage as a sorted list; run tests with coverage first, then click to send gaps to Claude for test generation

##### **v0.8.24** (2026-03-28)

**VCS + Code Intelligence**

- Library class / JAR source to Claude - right-click any class under "External Libraries" in the Project view and choose "Send to Claude"; Claudio injects the decompiled source via IntelliJ's PSI (Fernflower decompiler); truncated at 200 lines for large classes

##### **v0.8.23** (2026-03-28)

**VCS + Code Intelligence**

- Send Run Configurations to Claude - Tools > Send Run Configurations to Claude injects all run configs as a formatted list (name, type, working directory, program parameters); capped at 20; shows a notification if none exist

##### **v0.8.22** (2026-03-28)

**VCS + Code Intelligence**

- Send Bookmarks to Claude - Tools > Send Bookmarks to Claude injects all editor bookmarks as a numbered file:line list; bookmarks with descriptions include them in parentheses; shows a notification if no bookmarks are set

##### **v0.8.21** (2026-03-28)

**VCS + Code Intelligence**

- Send uncommitted diff to Claude - click the ⎇ button in the toolbar (or use Tools > Send Uncommitted Diff to Claude) to inject the full staged + unstaged git diff into the input bar; diff is truncated at 100 lines with a note if it exceeds that

##### **v0.8.20** (2026-03-28)

**Input Superpowers**

- /add-dir directory picker - click the 📁 button in the toolbar to open a native folder picker; selecting a directory inserts `/add-dir <path>` into the input bar so you can expand Claude's working context without typing paths manually

##### **v0.8.19** (2026-03-28)

**Input Superpowers**

- Debugger variable snapshot - "Send Debugger Context to Claude" now also includes the top 10 visible local variables from the current stack frame (e.g. `x = 42`, `str = "hello"`) so Claude has full state context, not just the pause location

##### **v0.8.18** (2026-03-28)

**Input Superpowers**

- Drag-and-drop files to input bar - drag any file from the Project tree, Finder, or anywhere into the Claudio input bar to insert `@relative/path` as context; regular text drag-and-drop still works normally

##### **v0.8.17** (2026-03-28)

**Input Superpowers**

- Image paste in input bar - press Cmd+V (macOS) / Ctrl+V (Win/Linux) when you have a screenshot or image on the clipboard; Claudio saves it to a temp PNG and injects the file path directly into the input bar so Claude can read it

##### **v0.8.16** (2026-03-28)

**Input Superpowers**

- Persistent "Always Allow" per project - permission dialogs now have a "Remember for this project" checkbox; checking it and clicking Allow saves the tool to `~/.claudio/allowlist/<project-hash>.json` and auto-approves future requests for that tool without showing a dialog

##### **v0.8.15** (2026-03-28)

**Context Superpowers**

- @symbol autocomplete - type `@FooClass` in the input bar to see matching files by name (⊞ prefix) alongside path matches; uses the IDE filename index so it works across all project types including Rider/C# projects

##### **v0.8.14** (2026-03-28)

**Context Superpowers**

- Debugger context injection - when the IDE debugger is paused at a breakpoint, "Send Debugger Context to Claude" (in the debugger toolbar and Tools menu) injects the session name and current file:line into Claudio so you can ask Claude to help debug the issue immediately

##### **v0.8.13** (2026-03-28)

**Context Superpowers**

- Git blame context - "Ask Claude to Explain" now automatically includes the git blame for the selected line (`Git blame: abc1234 Author on Date "commit message"`) so Claude knows who last touched the code and why; silently omitted when git history is unavailable

##### **v0.8.12** (2026-03-28)

**Context Superpowers**

- Send to Claude from Project view - right-click any file in the Project tree and choose "Send to Claude" to inject `@filepath` into the Claudio input bar instantly

##### **v0.8.11** (2026-03-28)

**Context Superpowers**

- Context window progress bar - the cost label now shows `$0.0234 · 1.2k / 200k` so you can see how much context you've consumed vs the 200k model limit at a glance

##### **v0.8.10** (2026-03-28)

**Context Superpowers**

- Send Current File action - press Cmd+Alt+F (macOS) / Ctrl+Alt+F (Win/Linux) anywhere in the editor to inject `@file:line` into the Claudio input bar; also available via right-click → "Send Current File to Claude"

##### **v0.8.9** (2026-03-28)

**Usage Analytics**

- Per-project cost tracking - every cost event is appended to `~/.claudio/costs.jsonl`; the History sidebar now shows "Cost today" and "All-time" totals at the bottom, summed across all projects

##### **v0.8.8** (2026-03-28)

**Usage Analytics**

- Live cost ticker in status bar - the IDE status bar widget now shows the running session cost alongside the generating/ready state (e.g. `⚡ Generating... · $0.0234`); hidden when cost is zero

##### **v0.8.7** (2026-03-28)

**Usage Analytics**

- Token/cost display per session - a subtle cost label below the status indicator shows running session cost and token count (e.g. `$0.0234 · 1.2k`); parsed from the CLI cost line; resets on new session

##### **v0.8.6** (2026-03-28)

**Deep IDE Integration**

- One-click MCP setup - the MCP Servers panel now has a + button; click it to add a new server via a form dialog (name, command, comma-separated args); the entry is written directly into ~/.claude/settings.json

##### **v0.8.5** (2026-03-28)

**Polish**

- Toolbar - the Claudio panel now has a toolbar strip with: + (new session), a model selector dropdown for new sessions (Default/Opus/Sonnet/Haiku), the existing ⚡ preset launcher, and ⚙ to open presets.json directly in the editor

##### **v0.8.4** (2026-03-28)

**IDE-Native Power Features**

- Inline diff preview - double-click any file in the "Changed Files" panel to open IntelliJ's native diff dialog showing git HEAD vs the current file content

##### **v0.8.3** (2026-03-28)

**IDE-Native Power Features**

- TODO/FIXME scan - Tools menu now has "Send TODOs to Claude"; scans the entire project via the IDE index and injects all TODO/FIXME items with file:line references into Claudio's input bar (capped at 50)

##### **v0.8.2** (2026-03-28)

**IDE-Native Power Features**

- Run/test failure injection - when a run configuration exits with a non-zero code, Claudio shows a balloon notification with a "Send to Claude" action that injects the run name, exit code, and captured output into the input bar

##### **v0.8.1** (2026-03-28)

**IDE-Native Power Features**

- "Ask Claude to Explain" right-click action - select any code in the editor, right-click, and choose "Ask Claude to Explain" to inject the selection and file:line reference into Claudio's input bar; falls back to the current line when nothing is selected

##### **v0.8.0** (2026-03-28)

**IDE-Native Power Features**

- Tab close button - each tab now has a × button; clicking it disposes the Claude session and removes the tab; the last tab cannot be closed

##### **v0.7.9** (2026-03-28)

**Polish**

- Theme-aware styling - all colors now adapt to dark/light IDE theme using JBColor; font size respects display DPI via JBUI.scale()

##### **v0.7.8** (2026-03-28)

**Polish**

- Session favorites - right-click any session in the History sidebar to Pin or Unpin it; pinned sessions float to the top with a ★ marker and persist across IDE restarts

##### **v0.7.7** (2026-03-28)

**Polish**

- Export session to Markdown - click ⬇ in the input bar to save the current session transcript as a `.md` file; the saved file opens in the editor automatically

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
