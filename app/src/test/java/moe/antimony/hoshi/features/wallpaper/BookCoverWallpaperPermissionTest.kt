package moe.antimony.hoshi.features.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCoverWallpaperPermissionTest {
    @Test
    fun lockScreenSwitchAllowsOnlyDisablingWhenCapabilityIsBlocked() {
        val blockedCapability = BookCoverWallpaperCapability(
            isSupported = true,
            isSetAllowed = false,
        )

        assertFalse(
            isLockScreenSwitchEnabled(
                settings = BookCoverWallpaperSettings(updateLockScreen = false),
                capability = blockedCapability,
            ),
        )
        assertTrue(
            isLockScreenSwitchEnabled(
                settings = BookCoverWallpaperSettings(updateLockScreen = true),
                capability = blockedCapability,
            ),
        )
    }

    @Test
    fun exportCanBeReenabledOnlyForThePersistedWriteTarget() {
        val grantedWriteUris = setOf("content://documents/current-cover")

        assertTrue(
            hasPersistedWritePermission(
                grantedWriteUris,
                "content://documents/current-cover",
            ),
        )
        assertFalse(
            hasPersistedWritePermission(
                grantedWriteUris,
                "content://documents/old-cover",
            ),
        )
        assertFalse(hasPersistedWritePermission(grantedWriteUris, null))
    }
}
