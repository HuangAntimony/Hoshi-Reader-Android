package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBehaviorSasayakiTest {
    @Test
    fun behaviorShowsKeepScreenOnBeforeSasayakiVolumeSeek() {
        assertEquals(
            listOf(
                R.string.reader_behavior_volume_keys_turn_pages,
                R.string.reader_behavior_volume_keys_navigate_popup_terms,
                R.string.reader_behavior_volume_keys_seek_sasayaki,
                R.string.reader_behavior_reverse_volume_key_direction,
                R.string.reader_behavior_keep_screen_on,
                R.string.reader_behavior_lock_current_orientation,
                R.string.reader_behavior_open_last_read_book_on_launch,
                R.string.reader_behavior_auto_check_updates,
            ),
            readerBehaviorRows(),
        )
    }

    @Test
    fun behaviorAlwaysShowsSasayakiVolumeSeek() {
        assertEquals(
            listOf(R.string.reader_behavior_volume_keys_seek_sasayaki),
            readerBehaviorSasayakiRows(),
        )
    }
}
