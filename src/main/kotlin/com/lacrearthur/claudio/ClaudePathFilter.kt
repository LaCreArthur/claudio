package com.lacrearthur.claudio

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

class ClaudeFileFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> =
        arrayOf(ClaudePathFilter(project))
}

private data class DeferredLink(
    val vf: VirtualFile,
    val start1: Int, val end1: Int,        // first clickable span (always set)
    val start2: Int = -1, val end2: Int = -1,  // second span for wrapped paths
)

class ClaudePathFilter(private val project: Project) : Filter {
    private val pathRegex = Regex("""(?:/[\w.@_\-/]+\.\w+|[\w@_][\w.@_\-]*/[\w.@_\-]+(?:/[\w.@_\-]+)*\.\w+)""")
    // CC CLI diff format: "      47   - removed" or "      47   + added"
    private val diffLineNumRegex = Regex("""^\s{4,}(\d+)\s+""")

    @Volatile private var prevLine = ""
    @Volatile private var prevLineStartOffset = 0
    // Deferred Update link: stored when path is found inside ⏺ Update(...), emitted when first
    // diff line number appears so the link jumps to the correct modified line.
    @Volatile private var pendingUpdateLink: DeferredLink? = null

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val lineOffset = entireLength - line.length
        val items = mutableListOf<Filter.ResultItem>()

        // New tool call starts - clear any unresolved pending link
        if (line.trimStart().startsWith("⏺ ")) pendingUpdateLink = null

        // Emit deferred Update link when we see the first diff line number
        pendingUpdateLink?.let { deferred ->
            diffLineNumRegex.find(line)?.let { m ->
                val lineNum = (m.groupValues[1].toIntOrNull() ?: 1).coerceAtLeast(1)
                val info = OpenFileHyperlinkInfo(project, deferred.vf, lineNum - 1)
                items += Filter.ResultItem(deferred.start1, deferred.end1, info)
                if (deferred.start2 >= 0) items += Filter.ResultItem(deferred.start2, deferred.end2, info)
                pendingUpdateLink = null
            }
        }

        // isUpdateContext: path is part of ⏺ Update(...) header (current or previous line for wraps)
        val isUpdateContext = line.contains("⏺ Update(") || prevLine.contains("⏺ Update(")

        // Direct path match on current line - skip if we already have a pending Update path
        if (pendingUpdateLink == null || !isUpdateContext) {
            pathRegex.findAll(line).forEach { match ->
                resolve(match.value)?.let { vf ->
                    val start = lineOffset + match.range.first
                    val end = lineOffset + match.range.last + 1
                    if (isUpdateContext) {
                        pendingUpdateLink = DeferredLink(vf, start, end)
                    } else {
                        items += Filter.ResultItem(start, end, OpenFileHyperlinkInfo(project, vf, 0))
                    }
                }
            }
        }

        // Wrapped path: CC CLI inserts \n at PTY column boundary - join prevLine + line to reconstruct
        if (items.isEmpty() && pendingUpdateLink == null && line.startsWith("  ") && prevLine.isNotEmpty()) {
            val prevTrimmed = prevLine.trimEnd()
            pathRegex.findAll(prevTrimmed + line.trim()).firstOrNull()?.let { match ->
                resolve(match.value)?.let { vf ->
                    val contentStart = line.indexOfFirst { !it.isWhitespace() }
                    val pathEndInLine = (match.range.last + 1 - prevTrimmed.length)
                        .coerceAtLeast(contentStart.takeIf { it >= 0 } ?: 0)
                    val s1 = prevLineStartOffset + match.range.first
                    val e1 = prevLineStartOffset + prevTrimmed.length
                    val s2 = if (contentStart >= 0) lineOffset + contentStart else -1
                    val e2 = if (contentStart >= 0) lineOffset + pathEndInLine else -1

                    if (isUpdateContext) {
                        pendingUpdateLink = if (match.range.first < prevTrimmed.length)
                            DeferredLink(vf, s1, e1, s2, e2)
                        else if (s2 >= 0) DeferredLink(vf, s2, e2)
                        else null
                    } else {
                        val info = OpenFileHyperlinkInfo(project, vf, 0)
                        if (match.range.first < prevTrimmed.length)
                            items += Filter.ResultItem(s1, e1, info)
                        if (s2 >= 0)
                            items += Filter.ResultItem(s2, e2, info)
                    }
                }
            }
        }

        prevLineStartOffset = lineOffset
        prevLine = line
        return if (items.isEmpty()) null else Filter.Result(items)
    }

    private fun resolve(path: String): VirtualFile? {
        val basePath = project.basePath
        return LocalFileSystem.getInstance().findFileByPath(path)
            ?: basePath?.let { LocalFileSystem.getInstance().findFileByPath("$it/$path") }
    }
}
