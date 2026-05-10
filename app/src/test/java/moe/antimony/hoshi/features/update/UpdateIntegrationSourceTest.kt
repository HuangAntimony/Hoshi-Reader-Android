package moe.antimony.hoshi.features.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateIntegrationSourceTest {
    @Test
    fun behaviorAndAboutScreensExposeGitHubUpdateControls() {
        val behavior = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderBehaviorView.kt").readText()
        val about = File("src/main/java/moe/antimony/hoshi/features/update/AboutView.kt").readText()
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()

        assertTrue(behavior.contains("Automatically Download Updates"))
        assertTrue(behavior.contains("updateSettings"))
        assertTrue(about.contains("Check for Updates"))
        assertTrue(about.contains("VERSION_NAME"))
        assertTrue(about.contains("VERSION_CODE"))
        assertTrue(appShell.contains("AboutScreen"))
        assertFalse(appShell.contains("This settings page is not implemented yet."))
    }

    @Test
    fun appStartupAndManifestWirePersistentUpdateWorkWithoutInstallPermission() {
        val application = File("src/main/java/moe/antimony/hoshi/HoshiApplication.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText() + File("../gradle/libs.versions.toml").readText()

        assertTrue(application.contains("UpdateScheduler.sync"))
        assertTrue(manifest.contains("UpdateDownloadCompleteReceiver"))
        assertFalse(manifest.contains("REQUEST_INSTALL_PACKAGES"))
        assertTrue(build.contains("work-runtime-ktx"))
    }
}
