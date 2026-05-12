package moe.antimony.hoshi.features.reader

import android.view.KeyEvent

internal sealed interface ReaderHardwareKeyAction {
    data class ReaderNavigation(val direction: ReaderNavigationDirection) : ReaderHardwareKeyAction
    data object SasayakiSeekForward : ReaderHardwareKeyAction
    data object SasayakiSeekBackward : ReaderHardwareKeyAction
}

internal fun readerNavigationDirectionForKeyEvent(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    settings: ReaderSettings,
): ReaderNavigationDirection? =
    (readerHardwareKeyActionForKeyEvent(
        keyCode = keyCode,
        action = action,
        repeatCount = repeatCount,
        settings = settings,
        sasayakiEnabled = false,
        hasSasayakiAudio = false,
    ) as? ReaderHardwareKeyAction.ReaderNavigation)?.direction

internal fun readerHardwareKeyActionForKeyEvent(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    settings: ReaderSettings,
    sasayakiEnabled: Boolean,
    hasSasayakiAudio: Boolean,
): ReaderHardwareKeyAction? {
    if (action != KeyEvent.ACTION_DOWN || repeatCount != 0) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_PAGE_DOWN -> ReaderHardwareKeyAction.ReaderNavigation(ReaderNavigationDirection.Forward)
        KeyEvent.KEYCODE_PAGE_UP -> ReaderHardwareKeyAction.ReaderNavigation(ReaderNavigationDirection.Backward)
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_UP,
        -> readerVolumeKeyAction(
            keyCode = keyCode,
            settings = settings,
            sasayakiEnabled = sasayakiEnabled,
            hasSasayakiAudio = hasSasayakiAudio,
        )
        else -> null
    }
}

private fun readerVolumeKeyAction(
    keyCode: Int,
    settings: ReaderSettings,
    sasayakiEnabled: Boolean,
    hasSasayakiAudio: Boolean,
): ReaderHardwareKeyAction? {
    if (settings.volumeKeysSeekSasayaki && sasayakiEnabled && hasSasayakiAudio) {
        return sasayakiSeekActionForVolumeKey(
            keyCode = keyCode,
            reverseDirection = settings.reverseVolumeKeyDirection,
        )
    }
    if (!settings.volumeKeysTurnPages) return null
    return ReaderHardwareKeyAction.ReaderNavigation(
        volumePageTurnDirectionForKey(
            keyCode = keyCode,
            reverseDirection = settings.reverseVolumeKeyDirection,
        ),
    )
}

private fun sasayakiSeekActionForVolumeKey(
    keyCode: Int,
    reverseDirection: Boolean,
): ReaderHardwareKeyAction =
    when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> if (reverseDirection) {
            ReaderHardwareKeyAction.SasayakiSeekForward
        } else {
            ReaderHardwareKeyAction.SasayakiSeekBackward
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (reverseDirection) {
            ReaderHardwareKeyAction.SasayakiSeekBackward
        } else {
            ReaderHardwareKeyAction.SasayakiSeekForward
        }
        else -> error("Unsupported volume key: $keyCode")
    }

private fun volumePageTurnDirectionForKey(
    keyCode: Int,
    reverseDirection: Boolean,
): ReaderNavigationDirection =
    when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (reverseDirection) {
            ReaderNavigationDirection.Backward
        } else {
            ReaderNavigationDirection.Forward
        }
        KeyEvent.KEYCODE_VOLUME_UP -> if (reverseDirection) {
            ReaderNavigationDirection.Forward
        } else {
            ReaderNavigationDirection.Backward
        }
        else -> error("Unsupported volume key: $keyCode")
    }
