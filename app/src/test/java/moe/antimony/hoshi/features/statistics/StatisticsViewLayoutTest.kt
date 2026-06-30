package moe.antimony.hoshi.features.statistics

import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsViewLayoutTest {
    @Test
    fun listBottomPaddingDoesNotDuplicateMainShellNavigationPadding() {
        assertEquals(24, statisticsListBottomPaddingDp())
    }
}
