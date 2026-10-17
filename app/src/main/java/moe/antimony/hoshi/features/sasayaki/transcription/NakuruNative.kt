package moe.antimony.hoshi.features.sasayaki.transcription

import com.sun.jna.Callback
import com.sun.jna.IntegerType
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure

internal interface NakuruNative : Library {
    fun nakuru_create(pack: String, performance: Int): Pointer?
    fun nakuru_transcribe(recognizer: Pointer, read: Read, count: Size, emit: Emit, context: Pointer?): Int
    fun nakuru_error(): String
    fun nakuru_destroy(recognizer: Pointer)

    fun interface Read : Callback {
        fun invoke(context: Pointer?, offset: Size, count: Size, samples: Pointer): Int
    }
    fun interface Emit : Callback {
        fun invoke(context: Pointer?, segment: Pointer): Int
    }
    class Size(value: Long = 0) : IntegerType(Native.SIZE_T_SIZE, value, true) {
        override fun toByte() = toLong().toByte()
        override fun toShort() = toLong().toShort()
    }

    @Structure.FieldOrder("text", "start", "end")
    class Token(pointer: Pointer) : Structure(pointer) {
        @JvmField var text: Pointer? = null
        @JvmField var start = 0.0
        @JvmField var end = 0.0
        init { read() }
    }

    @Structure.FieldOrder("text", "start", "end", "tokens", "count")
    class Segment(pointer: Pointer) : Structure(pointer) {
        @JvmField var text: Pointer? = null
        @JvmField var start = 0.0
        @JvmField var end = 0.0
        @JvmField var tokens: Pointer? = null
        @JvmField var count = Size()
        init { read() }
    }
}
