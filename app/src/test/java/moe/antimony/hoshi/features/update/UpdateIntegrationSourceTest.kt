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
        val prompt = File("src/main/java/moe/antimony/hoshi/features/update/DownloadedUpdatePrompt.kt").readText()
        val mainActivity = File("src/main/java/moe/antimony/hoshi/MainActivity.kt").readText()
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()

        assertTrue(behavior.contains("Automatically Download Updates"))
        assertTrue(behavior.contains("updateSettings"))
        assertTrue(about.contains("Check for Updates"))
        assertTrue(about.contains("VERSION_NAME"))
        assertTrue(about.contains("VERSION_CODE"))
        assertTrue(about.contains("Hoshi-Reader-Android"))
        assertTrue(about.contains("If you like this app, consider starring the project on GitHub."))
        assertTrue(prompt.contains("Update Downloaded"))
        assertTrue(prompt.contains("shouldPromptForInstall"))
        assertTrue(mainActivity.contains("DownloadedUpdatePrompt"))
        assertTrue(appShell.contains("AboutScreen"))
        assertFalse(appShell.contains("This settings page is not implemented yet."))
    }

    @Test
    fun appStartupAndManifestWirePersistentUpdateWorkAndInstallerAccess() {
        val application = File("src/main/java/moe/antimony/hoshi/HoshiApplication.kt").readText()
        val scheduler = File("src/main/java/moe/antimony/hoshi/features/update/UpdateScheduler.kt").readText()
        val installer = File("src/main/java/moe/antimony/hoshi/features/update/UpdateInstaller.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val build = File("build.gradle.kts").readText() + File("../gradle/libs.versions.toml").readText()

        assertTrue(application.contains("UpdateScheduler.sync"))
        assertTrue(scheduler.contains("OneTimeWorkRequestBuilder<UpdateCheckWorker>"))
        assertTrue(scheduler.contains("UniqueImmediateWorkName"))
        assertTrue(manifest.contains("UpdateDownloadCompleteReceiver"))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("REQUEST_INSTALL_PACKAGES"))
        assertTrue(installer.contains("canRequestPackageInstalls"))
        assertTrue(installer.contains("ACTION_MANAGE_UNKNOWN_APP_SOURCES"))
        assertTrue(build.contains("work-runtime-ktx"))
    }
}
