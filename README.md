<div align="center">

# Claudio

**Claude Code on steroids for JetBrains IDEs.**

> [!WARNING]
> **Early release** - actively in development. This is prototype-stage software. Expect rough edges, not a polished production plugin. Feedback, bug reports, and contributions are very welcome!

Claudio adds the missing native UX on top of the existing `claude code`, with zero compromises: all features, all models, fully compliant with Anthropic's Terms of Service.

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/com.lacrearthur.claudio?label=marketplace)](https://plugins.jetbrains.com/plugin/com.lacrearthur.claudio)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.lacrearthur.claudio)](https://plugins.jetbrains.com/plugin/com.lacrearthur.claudio)
[![GitHub Stars](https://img.shields.io/github/stars/LaCreArthur/claudio?color=ffcb47&labelColor=black&style=flat-square)](https://github.com/LaCreArthur/claudio/stargazers)
[![GitHub Issues](https://img.shields.io/github/issues/LaCreArthur/claudio?color=ff80eb&labelColor=black&style=flat-square)](https://github.com/LaCreArthur/claudio/issues)

</div>

---

## Why Claudio is different

Most Claude integrations for IDEs re-implement the Claude API themselves -their own auth, their own proxy, their own model access. That means handing over credentials, trusting a third party, and hoping they stay within Anthropic's Terms of Service.

Claudio doesn't do any of that. It runs **your existing `claude code`** directly. Your auth is the CLI's auth. Your settings are the CLI's settings. This plugin never reads, stores, or touches your credentials -it literally cannot, because it never has access to them.

The practical upside: **everything Claude Code can do, Claudio can do** -because it *is* Claude Code, just with a better JetBrains UX on top. New CLI features land automatically. Your existing config, your CLAUDE.md files, your MCP servers -all of it just works.

It's also the only way to use Claude Code with a **Max or Pro subscription** inside a JetBrains IDE that's fully ToS-compliant: no API key needed, no OAuth token extraction, no workarounds.

## Features

- **Interactive session** -full Claude Code terminal in a side panel
- **Native permission dialogs** -Allow / Deny prompts as proper IDE dialogs, not raw CLI prompts
- **Clickable file paths** -every path in Claude's output is a hyperlink; Diffs jump to the first modified line
- **Send Selection** (`Cmd+Alt+K`) -send selected editor code straight to Claude's input
- **Prompt history** -↑↓ buttons to cycle previous prompts
- **Permission mode badge** -live ⚡ indicator; `Shift+Tab` cycles modes (default → auto → bypassPermissions)
- **Slash command autocomplete** -popup triggered by `/`
- **Multi-line input** -`Cmd+Enter` for newlines, `Enter` to send
- **More to come** -Early stage of development; send feedback and feature requests!

## Requirements

- JetBrains IDE 2025.3+ (Rider, IntelliJ IDEA, etc.)
- [Claude Code](https://docs.anthropic.com/en/docs/claude-code) installed and authenticated

## Install

**From JetBrains Marketplace** (recommended):
> Settings → Plugins → Marketplace → search **Claudio**

**Manual:**
1. Download the latest `.zip` from [Releases](https://github.com/LaCreArthur/claudio/releases)
2. Settings → Plugins → ⚙️ → Install Plugin from Disk

## Build from Source

```bash
git clone https://github.com/LaCreArthur/claudio.git
cd claudio
./gradlew buildPlugin
# Output: build/distributions/claudio-*.zip
```

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

[MIT](LICENSE)

---

[![Star History](https://api.star-history.com/svg?repos=LaCreArthur/claudio&type=Date)](https://star-history.com/#LaCreArthur/claudio&Date)
