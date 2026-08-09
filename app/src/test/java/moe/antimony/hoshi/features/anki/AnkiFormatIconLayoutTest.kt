package moe.antimony.hoshi.features.anki

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiFormatIconLayoutTest {
    @Test
    fun smallVariantsShrinkInsideTheSameStableSlot() {
        val layouts = AnkiFormatIcon.entries.associateWith(::ankiFormatIconLayout)

        assertEquals(setOf(24.dp), layouts.values.map { it.slotSize }.toSet())
        assertEquals(
            setOf(24.dp),
            listOf(AnkiFormatIcon.Square, AnkiFormatIcon.Circle, AnkiFormatIcon.Diamond)
                .map { layouts.getValue(it).glyphSize }
                .toSet(),
        )
        assertEquals(
            setOf(18.dp),
            listOf(AnkiFormatIcon.SquareSmall, AnkiFormatIcon.CircleSmall, AnkiFormatIcon.DiamondSmall)
                .map { layouts.getValue(it).glyphSize }
                .toSet(),
        )
    }
}
