package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class WorkingDirectoryManager {
    private static final Logger LOG = Logger.getInstance(WorkingDirectoryManager.class);

    private final Function<Void, JsonObject> configReader;
    private final java.util.function.Consumer<JsonObject> configWriter;

    public WorkingDirectoryManager(
            Function<Void, JsonObject> configReader,
            java.util.function.Consumer<JsonObject> configWriter) {
        this.configReader = configReader;
        this.configWriter = configWriter;
    }

    /**
     * Determine the working directory for a project.
     * Falls back to project base path, then user home.
     * Checks for custom working directory in plugin settings.
     */
    public static String resolveWorkingDirectory(Project project) {
        String projectPath = project.getBasePath();
        if (projectPath == null || !new File(projectPath).exists()) {
            String userHome = System.getProperty("user.home");
            LOG.warn("[WorkingDirectoryManager] Project path unavailable, using user home: " + userHome);
            return userHome;
        }

        try {
            com.github.claudecodegui.PluginSettingsService settingsService =
                new com.github.claudecodegui.PluginSettingsService();
            String customWorkingDir = settingsService.getCustomWorkingDirectory(projectPath);
            if (customWorkingDir != null && !customWorkingDir.isEmpty()) {
                File workingDirFile = new File(customWorkingDir);
                if (!workingDirFile.isAbsolute()) {
                    workingDirFile = new File(projectPath, customWorkingDir);
                }
                if (workingDirFile.exists() && workingDirFile.isDirectory()) {
                    String resolvedPath = workingDirFile.getAbsolutePath();
                    LOG.info("[WorkingDirectoryManager] Using custom working directory: " + resolvedPath);
                    return resolvedPath;
                } else {
                    LOG.warn("[WorkingDirectoryManager] Custom working directory does not exist: "
                        + workingDirFile.getAbsolutePath() + ", falling back to project root");
                }
            }
        } catch (Exception e) {
            LOG.warn("[WorkingDirectoryManager] Failed to read custom working directory: " + e.getMessage());
        }

        return projectPath;
    }

    public String getCustomWorkingDirectory(String projectPath) {
        JsonObject config = configReader.apply(null);

        if (!config.has("workingDirectories") || config.get("workingDirectories").isJsonNull()) {
            return null;
        }

        JsonObject workingDirs = config.getAsJsonObject("workingDirectories");

        if (workingDirs.has(projectPath) && !workingDirs.get(projectPath).isJsonNull()) {
            return workingDirs.get(projectPath).getAsString();
        }

        return null;
    }

    public void setCustomWorkingDirectory(String projectPath, String customWorkingDir) throws IOException {
        JsonObject config = configReader.apply(null);

        if (!config.has("workingDirectories")) {
            config.add("workingDirectories", new JsonObject());
        }

        JsonObject workingDirs = config.getAsJsonObject("workingDirectories");

        if (customWorkingDir == null || customWorkingDir.trim().isEmpty()) {
            workingDirs.remove(projectPath);
        } else {
            workingDirs.addProperty(projectPath, customWorkingDir.trim());
        }

        configWriter.accept(config);
        LOG.info("[WorkingDirectoryManager] Set custom working directory for " + projectPath + ": " + customWorkingDir);
    }

    public Map<String, String> getAllWorkingDirectories() {
        Map<String, String> result = new HashMap<>();
        JsonObject config = configReader.apply(null);

        if (!config.has("workingDirectories") || config.get("workingDirectories").isJsonNull()) {
            return result;
        }

        JsonObject workingDirs = config.getAsJsonObject("workingDirectories");
        for (String key : workingDirs.keySet()) {
            result.put(key, workingDirs.get(key).getAsString());
        }

        return result;
    }
}
