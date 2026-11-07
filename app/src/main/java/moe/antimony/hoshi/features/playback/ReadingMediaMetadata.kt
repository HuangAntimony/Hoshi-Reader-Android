package moe.antimony.hoshi.features.playback

import androidx.media3.common.MediaMetadata

internal fun readingMediaMetadata(
    title: String?,
    sentence: String?,
    artworkData: ByteArray?,
): MediaMetadata = MediaMetadata.Builder()
    .setTitle(title)
    .setArtist(sentence.orEmpty().take(160))
    .setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
    .build()
