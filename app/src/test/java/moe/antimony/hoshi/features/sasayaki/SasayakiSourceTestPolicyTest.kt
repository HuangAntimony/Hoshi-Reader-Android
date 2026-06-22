package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SasayakiSourceTestPolicyTest {
    @Test
    fun replacedBehaviorSeamsDoNotKeepSourceShapeTests() {
        val sasayakiTests = File("src/test/java/moe/antimony/hoshi/features/sasayaki")

        listOf(
            "SasayakiAudioRestoreResultCoordinatorSourceTest.kt",
            "SasayakiPlaybackLifecycleControllerSourceTest.kt",
            "SasayakiPlaybackPersistenceStateSourceTest.kt",
            "SasayakiTemporaryPlaybackRestoreCoordinatorSourceTest.kt",
        ).forEach { fileName ->
            assertFalse("$fileName should be covered by behavior tests.", File(sasayakiTests, fileName).exists())
        }
    }
}
