package moe.antimony.hoshi.features.sasayaki.transcription

/** JNI is private to the transcription decoder; callers close each handle once. */
internal object NativeSasayakiAudio {
    init { SasayakiNativeLibraries.loadLibrary("hoshiaudio_jni") }

    external fun open(fd: Int, offset: Long, length: Long, from: Double): Long
    external fun read(handle: Long): FloatArray?
    external fun close(handle: Long)
}
