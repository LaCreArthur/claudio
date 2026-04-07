package com.lacrearthur.claudio

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
                val name = meta?.get("name")?.asString
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

    private fun loadSessionMeta(): Map<String, JsonObject> {
        val dir = File(System.getProperty("user.home"), ".claude/sessions")
        if (!dir.isDirectory) return emptyMap()
        val map = mutableMapOf<String, JsonObject>()
        (dir.listFiles(FileFilter { it.extension == "json" }) ?: emptyArray()).forEach { file ->
            try {
                val obj = JsonParser.parseString(file.readText()).asJsonObject
                val sid = obj.get("sessionId")?.asString ?: return@forEach
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
                    val obj = try {
                        JsonParser.parseString(line).asJsonObject
                    } catch (_: Exception) { continue }
                    if (obj.get("type")?.asString != "user") continue
                    val message = obj.getAsJsonObject("message") ?: continue
                    val content = message.get("content")?.asString ?: continue
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
                    val obj = try {
                        JsonParser.parseString(l).asJsonObject
                    } catch (_: Exception) { continue }
                    if (obj.get("type")?.asString != "file-history-snapshot") continue
                    // Skip incremental updates - only show primary checkpoints
                    val snapshot = obj.getAsJsonObject("snapshot") ?: continue
                    if (snapshot.get("isSnapshotUpdate")?.asBoolean == true) continue

                    val timestamp = snapshot.get("timestamp")?.asString ?: continue

                    // Extract backed-up files from the parsed snapshot
                    val files = extractBackedUpFiles(snapshot, historyDir)
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

    /** Extract files with actual backups from a parsed snapshot object. */
    private fun extractBackedUpFiles(snapshot: JsonObject, historyDir: File): List<CheckpointFile> {
        val files = mutableListOf<CheckpointFile>()
        val backups = snapshot.getAsJsonObject("trackedFileBackups") ?: return files
        for ((path, entry) in backups.entrySet()) {
            val entryObj = try { entry.asJsonObject } catch (_: Exception) { continue }
            val backupName = entryObj.get("backupFileName")?.asString ?: continue
            val version = entryObj.get("version")?.asInt ?: 1
            if (File(historyDir, backupName).exists()) {
                files.add(CheckpointFile(path, backupName, version))
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
