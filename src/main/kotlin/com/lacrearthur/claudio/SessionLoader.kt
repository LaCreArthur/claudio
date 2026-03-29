package com.lacrearthur.claudio

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.File
import java.io.FileFilter
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.Date

data class SessionInfo(
    val sessionId: String,
    val name: String,
    val lastModified: Long,
    val sizeKb: Long,
)

data class CheckpointInfo(
    val timestamp: String,
    val epochMillis: Long,
    val files: List<CheckpointFile>,
)

data class CheckpointFile(
    val filePath: String,
    val backupFileName: String,
    val version: Int,
)

object SessionLoader {
    private val log = Logger.getInstance("Claudio")
    private val commandTagRegex = Regex("<[^>]+>")

    private fun projectDir(project: Project): File? {
        val basePath = project.basePath ?: return null
        val encoded = basePath.replace("/", "-")
        return File(System.getProperty("user.home"), ".claude/projects/$encoded")
            .takeIf { it.isDirectory }
    }

    fun loadSessions(project: Project): List<SessionInfo> {
        val projectDir = projectDir(project) ?: return emptyList()

        val sessionMeta = loadSessionMeta()

        return (projectDir.listFiles(FileFilter { it.extension == "jsonl" }) ?: emptyArray())
            .map { file ->
                val sid = file.nameWithoutExtension
                val meta = sessionMeta[sid]
                val name = meta?.optString("name")
                    ?: extractFirstUserMessage(file)
                    ?: sid.take(8)
                SessionInfo(
                    sessionId = sid,
                    name = name,
                    lastModified = file.lastModified(),
                    sizeKb = file.length() / 1024,
                )
            }
            .sortedByDescending { it.lastModified }
    }

    private fun loadSessionMeta(): Map<String, JsonObj> {
        val dir = File(System.getProperty("user.home"), ".claude/sessions")
        if (!dir.isDirectory) return emptyMap()
        val map = mutableMapOf<String, JsonObj>()
        (dir.listFiles(FileFilter { it.extension == "json" }) ?: emptyArray()).forEach { file ->
            try {
                val obj = JsonObj(file.readText())
                val sid = obj.optString("sessionId") ?: return@forEach
                map[sid] = obj
            } catch (_: Exception) {}
        }
        return map
    }

    private fun extractFirstUserMessage(file: File): String? {
        try {
            BufferedReader(FileReader(file)).use { reader ->
                var count = 0
                while (count < 50) {
                    val line = reader.readLine() ?: break
                    count++
                    if (!line.contains("\"type\":\"user\"")) continue
                    val obj = JsonObj(line)
                    if (obj.optString("type") != "user") continue
                    val message = obj.optObj("message") ?: continue
                    val content = message.optString("content") ?: continue
                    val clean = commandTagRegex.replace(content, "").trim()
                        .lineSequence()
                        .firstOrNull { it.isNotBlank() && !it.startsWith("Caveat:") }
                        ?.take(100)
                        ?.trim()
                    if (!clean.isNullOrEmpty()) return clean
                }
            }
        } catch (e: Exception) {
            log.warn("[SESSIONS] Failed to read first message from ${file.name}: ${e.message}")
        }
        return null
    }

    /** Load checkpoints from the most recent session for this project. */
    fun loadCheckpoints(project: Project): List<CheckpointInfo> {
        val projectDir = projectDir(project) ?: return emptyList()

        // Find most recent session JSONL
        val jsonlFile = (projectDir.listFiles(FileFilter { it.extension == "jsonl" }) ?: emptyArray())
            .maxByOrNull { it.lastModified() } ?: return emptyList()

        val sessionId = jsonlFile.nameWithoutExtension
        val historyDir = File(System.getProperty("user.home"), ".claude/file-history/$sessionId")

        val checkpoints = mutableListOf<CheckpointInfo>()
        try {
            BufferedReader(FileReader(jsonlFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.contains("\"file-history-snapshot\"")) continue
                    val obj = JsonObj(l)
                    if (obj.optString("type") != "file-history-snapshot") continue
                    // Skip incremental updates - only show primary checkpoints
                    if (l.contains("\"isSnapshotUpdate\":true")) continue

                    val snapshot = obj.optObj("snapshot") ?: continue
                    val timestamp = snapshot.optString("timestamp") ?: continue

                    // Extract backed-up files from the raw JSON
                    val files = extractBackedUpFiles(l, historyDir)
                    if (files.isEmpty()) continue

                    val epochMs = parseIsoTimestamp(timestamp)
                    checkpoints.add(CheckpointInfo(timestamp, epochMs, files))
                }
            }
        } catch (e: Exception) {
            log.warn("[CHECKPOINTS] Failed to read checkpoints: ${e.message}")
        }
        return checkpoints.sortedByDescending { it.epochMillis }
    }

    /** Extract files with actual backups (backupFileName != null) from a snapshot JSON line. */
    private fun extractBackedUpFiles(line: String, historyDir: File): List<CheckpointFile> {
        val files = mutableListOf<CheckpointFile>()
        // Find trackedFileBackups object and extract entries
        val marker = "\"trackedFileBackups\":"
        val idx = line.indexOf(marker)
        if (idx < 0) return files

        val afterMarker = line.substring(idx + marker.length).trimStart()
        if (!afterMarker.startsWith('{')) return files

        val end = JsonUtils.findClosingBrace(afterMarker, 0)
        if (end <= 1) return files // empty object {}

        val backupsJson = afterMarker.substring(0, end + 1)

        // Parse file entries: "path/to/file": {"backupFileName": "hash@v1", "version": 1, ...}
        val entryRegex = Regex(""""([^"]+)":\s*\{[^}]*"backupFileName":\s*"([^"]+)"[^}]*"version":\s*(\d+)""")
        for (match in entryRegex.findAll(backupsJson)) {
            val filePath = match.groupValues[1]
            val backupName = match.groupValues[2]
            val version = match.groupValues[3].toIntOrNull() ?: 1
            // Only include if backup file actually exists
            if (File(historyDir, backupName).exists()) {
                files.add(CheckpointFile(filePath, backupName, version))
            }
        }
        return files
    }

    private fun parseIsoTimestamp(iso: String): Long {
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.parse(iso)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    /** Get the file-history directory for the most recent session. */
    fun getHistoryDir(project: Project): File? {
        val projectDir = projectDir(project) ?: return null
        val jsonlFile = (projectDir.listFiles(FileFilter { it.extension == "jsonl" }) ?: emptyArray())
            .maxByOrNull { it.lastModified() } ?: return null
        return File(System.getProperty("user.home"), ".claude/file-history/${jsonlFile.nameWithoutExtension}")
    }

    fun formatTimestamp(epochMillis: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = now - epochMillis
        val diffMin = diffMs / 60_000
        val diffHrs = diffMs / 3_600_000
        val diffDays = diffMs / 86_400_000
        return when {
            diffMin < 1 -> "just now"
            diffMin < 60 -> "${diffMin}m ago"
            diffHrs < 24 -> "${diffHrs}h ago"
            diffDays < 7 -> "${diffDays}d ago"
            else -> SimpleDateFormat("MMM d").format(Date(epochMillis))
        }
    }

}
