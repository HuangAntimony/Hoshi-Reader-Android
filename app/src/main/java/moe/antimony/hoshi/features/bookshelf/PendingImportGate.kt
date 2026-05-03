package moe.antimony.hoshi.features.bookshelf

internal class PendingImportGate<T> {
    private var activeValue: T? = null

    fun tryStart(value: T): Boolean {
        if (activeValue != null) {
            return false
        }
        activeValue = value
        return true
    }

    fun finish(value: T) {
        if (activeValue == value) {
            activeValue = null
        }
    }
}
