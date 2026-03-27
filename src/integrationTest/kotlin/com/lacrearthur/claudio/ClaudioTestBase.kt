package com.lacrearthur.claudio

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.driver.sdk.openToolWindow
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.nio.file.Files

/**
 * Base for Claudio integration tests.
 *
 * Run `./gradlew buildPlugin` first - tests require the ZIP in build/distributions/.
 *
 * IDE version: pinned to 2025.3.1 (build 253.29346.138) via claudio.test.ide.dir system property,
 * which build.gradle resolves from Gradle's transform cache. Without it, Starter downloads the
 * latest EAP (which may not load the plugin correctly).
 *
 * Tool window ID: "Claude" (matches plugin.xml <toolWindow id="Claude">).
 * HookServer starts lazily on ClaudePanel init (first tool window activation).
 * [withDriver] handles all standard setup: IDE boot -> project open -> tool window -> hook server ready.
 *
 * Driver SDK note:
 * - Use explicit KClass for member service calls: `service(MyService::class, project)`
 * - Extension functions (openToolWindow, singleProject, waitForProjectOpen) are imported
 *   and callable directly when `this` is [Driver] inside [withDriver] blocks.
 */
abstract class ClaudioTestBase {

    protected lateinit var context: IDETestContext

    companion object {
        const val TOOL_WINDOW_ID = "Claude"
    }

    @BeforeEach
    open fun setUp() {
        val pluginZip = File("build/distributions")
            .listFiles { f -> f.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }
            ?: error("Run './gradlew buildPlugin' first")

        // Create a minimal temp directory for IDEA to open as a project.
        // Without a project, waitForProjectOpen() times out (Welcome Screen).
        val projectDir = Files.createTempDirectory("claudio-test-project")

        // Pin to 2025.3 (sinceBuild=253). Without withVersion, Starter downloads latest EAP.
        // TestCase API is the documented way to set version (withVersion chains on TestCase).
        context = Starter.newContext(
            "ClaudioTest",
            TestCase(IdeProductProvider.IU, LocalProjectInfo(projectDir))
                .withVersion("2025.3")
        )
            .also { it.pluginConfigurator.installPluginFromPath(pluginZip.toPath()) }
            // Trust the temp project to skip the "Trust Project?" dialog.
            .addProjectToTrustedLocations(projectDir)
    }

    /**
     * Launch IDE, run standard setup (project open + tool window + hook server),
     * then invoke [block] with [RemoteClaudioTestService] as argument.
     * Closes IDE after [block] completes or throws.
     *
     * Inside [block]: `this` is [Driver] — all Driver SDK extensions callable without prefix.
     */
    protected fun withDriver(block: Driver.(RemoteClaudioTestService) -> Unit) {
        context.runIdeWithDriver().useDriverAndCloseIde {
            // this: Driver (receiver)
            waitForProjectOpen()
            val project = singleProject()

            // Open the tool window to trigger ClaudePanel init -> HookServer.start()
            openToolWindow(TOOL_WINDOW_ID)

            // Use member function with explicit KClass to avoid ambiguity with reified extensions
            val svc: RemoteClaudioTestService = service(RemoteClaudioTestService::class, project)

            // Wait for hook server to register its port
            waitForHookServer(svc)

            block(svc)
        }
    }

    /** Poll until [RemoteClaudioTestService.getHookServerPort] > 0. */
    protected fun Driver.waitForHookServer(svc: RemoteClaudioTestService, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (svc.getHookServerPort() > 0) return
            Thread.sleep(500)
        }
        error("Hook server did not start within ${timeoutMs}ms")
    }

    /** Poll until [condition] returns true, or throw after [timeoutMs]. */
    protected fun waitFor(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(200)
        }
        error("Timed out waiting for: $description")
    }
}
