package com.lacrearthur.claudio

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.security.SecureRandom
import java.util.Base64

private val log = Logger.getInstance("ClaudioIdeLockfile")

/**
 * Manages the ~/.claude/ide/<port>.lock file that Claude Code CLI uses
 * to auto-discover running IDE instances via the /ide protocol.
 *
 * The CLI reads all .lock files, matches workspaceFolders against its cwd,
 * verifies PID liveness, and connects via ws://127.0.0.1:<port>.
 */
object IdeLockfileManager {

    private val ideDir = File(System.getProperty("user.home"), ".claude/ide")

    fun write(port: Int, project: Project) {
        try {
            ideDir.mkdirs()
            val lockfile = File(ideDir, "$port.lock")
            val basePath = project.basePath ?: return
            val pid = ProcessHandle.current().pid()
            val token = generateToken()
            val ideName = "JetBrains ${ApplicationInfo.getInstance().versionName}"
            val json = """{"workspaceFolders":["$basePath"],"pid":$pid,"ideName":"$ideName","transport":"ws","runningInWindows":false,"authToken":"$token"}"""
            lockfile.writeText(json)
            log.warn("[IDE] lockfile written: ${lockfile.absolutePath}")
        } catch (e: Exception) {
            log.error("[IDE] failed to write lockfile", e)
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun delete(port: Int) {
        try {
            val lockfile = File(ideDir, "$port.lock")
            if (lockfile.delete()) {
                log.warn("[IDE] lockfile deleted: ${lockfile.absolutePath}")
            }
        } catch (e: Exception) {
            log.warn("[IDE] failed to delete lockfile: ${e.message}")
        }
    }
}
