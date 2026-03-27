package com.lacrearthur.claudio

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File

private val log = Logger.getInstance("ClaudioHookInstaller")

/**
 * Writes the hook script and registers it in Claude Code settings.json.
 * Idempotent - safe to call on every plugin startup.
 *
 * Uses proper JSON parse/modify/write - no string surgery.
 */
object HookInstaller {

    private val claudeDir    = File(System.getProperty("user.home"), ".claude")
    private val hookScript   = File(claudeDir, "claudio-hook.sh")
    private val settingsFile = File(claudeDir, "settings.json")
    private val gson         = GsonBuilder().setPrettyPrinting().create()

    private val HOOK_TYPES = listOf(
        "PreToolUse"        to 120_000,
        "PermissionRequest" to 120_000,
        "Notification"      to   5_000,
    )

    fun install() {
        try {
            writeHookScript()
            registerInSettings()
            log.warn("[HOOKS] installation complete")
        } catch (e: Exception) {
            log.error("[HOOKS] installation failed", e)
        }
    }

    private fun writeHookScript() {
        claudeDir.mkdirs()
        hookScript.writeText(
            "#!/bin/bash\n" +
            "# Claudio IDE Hook - forwards Claude Code events to the plugin\n" +
            "# Auto-generated. Do not edit.\n" +
            "if [ -z \"\$CLAUDIO_HOOK_PORT\" ]; then\n" +
            "  exit 0\n" +
            "fi\n" +
            "cat | curl -s --max-time 120 -X POST -H \"Content-Type: application/json\" " +
            "-d @- \"http://127.0.0.1:\$CLAUDIO_HOOK_PORT/event\" 2>/dev/null\n"
        )
        hookScript.setExecutable(true)
        log.warn("[HOOKS] wrote ${hookScript.absolutePath}")
    }

    private fun registerInSettings() {
        val root: JsonObject = if (settingsFile.exists()) {
            try {
                JsonParser.parseString(settingsFile.readText()).asJsonObject
            } catch (e: Exception) {
                log.warn("[HOOKS] settings.json unreadable (${e.message}), starting fresh")
                JsonObject()
            }
        } else {
            JsonObject()
        }

        var changed = false
        val hooksObj: JsonObject = root.getAsJsonObject("hooks") ?: JsonObject().also {
            root.add("hooks", it)
            changed = true
        }

        for ((hookType, timeout) in HOOK_TYPES) {
            val arr: JsonArray = hooksObj.getAsJsonArray(hookType) ?: JsonArray().also {
                hooksObj.add(hookType, it)
            }

            val alreadyRegistered = arr.any { elem ->
                elem.asJsonObject
                    .getAsJsonArray("hooks")
                    ?.any { h -> h.asJsonObject.get("command")?.asString?.contains("claudio-hook") == true }
                    ?: false
            }

            if (!alreadyRegistered) {
                arr.add(buildEntry(timeout))
                log.warn("[HOOKS] registered $hookType")
                changed = true
            }
        }

        if (changed) {
            claudeDir.mkdirs()
            settingsFile.writeText(gson.toJson(root))
            log.warn("[HOOKS] settings.json updated")
        } else {
            log.warn("[HOOKS] all hook types already registered in settings.json")
        }
    }

    private fun buildEntry(timeout: Int): JsonObject {
        val hookEntry = JsonObject().apply {
            addProperty("type", "command")
            addProperty("command", "bash ${hookScript.absolutePath}")
            addProperty("timeout", timeout)
        }
        return JsonObject().apply {
            addProperty("matcher", "")
            add("hooks", JsonArray().apply { add(hookEntry) })
        }
    }
}
