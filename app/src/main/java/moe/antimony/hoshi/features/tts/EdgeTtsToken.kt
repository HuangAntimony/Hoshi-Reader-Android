package moe.antimony.hoshi.features.tts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Edge's Sec-MS-GEC v2 wire algorithm, verified against edge-tts 7.3.1.
 * Protocol analysis: https://github.com/rany2/edge-tts/issues/490#issuecomment-6126621790
 * Browser capture: https://github.com/rany2/edge-tts/pull/492
 */
internal object EdgeTtsToken {
    fun generate(unixSeconds: Long, trustedClientToken: String): String {
        val seconds = unixSeconds + 11_644_473_600L
        val ticks = (seconds - seconds % 300) * 10_000_000L
        // This exact prefix is protocol data included in both hashes.
        val data = ("Copyright (C) Microsoft Corporation. All rights reserved." +
            ticks + trustedClientToken).toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return "${cs64(data, digest).hex()}-${digest.hex()}"
    }

    private fun cs64(data: ByteArray, digest: ByteArray): ByteArray {
        val keys = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = keys.int or 1
        val keyB = keys.int or 1
        // CS64 consumes pairs of little-endian words, zero-padding the final pair.
        val words = ByteBuffer.wrap(data.copyOf((data.size + 7) / 8 * 8)).order(ByteOrder.LITTLE_ENDIAN)
        var swapLast = 0
        var swapSum = 0
        var state = 0
        var sum = 0
        while (words.hasRemaining()) {
            val x = words.int
            val y = words.int
            // Int overflow deliberately implements unsigned arithmetic modulo 2^32.
            var q = rotate(rotate(x) * 0xEF0569FB.toInt() + x * keyA) * 0x76F50F34
            swapSum += rotate(q) * 0xC3EFEA97.toInt() + q
            q = rotate(rotate(y) * 0xC31713DB.toInt() + y * keyB) * 0x1881CE3C
            swapLast = rotate(q) * 0x35BD1EC9 + q
            swapSum += swapLast

            state = (state + x) * keyA
            state = rotate(state) * 0xCF98B111.toInt()
            state = rotate(state) * 0x87085B9F.toInt()
            state = rotate(state) * 0x12CEB96D
            state = rotate(state) * 0x257E1D83
            sum += state
            var next = (state + y) * keyB
            next = rotate(next) * 0xA27416F5.toInt()
            next = rotate(next) * 0xD38396FF.toInt()
            state = rotate(rotate(next) * 0x7C932B89) * 0xBFA49F69.toInt()
            sum += state
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(swapLast xor state).putInt(swapSum xor sum).array()
    }

    private fun rotate(value: Int) = Integer.rotateLeft(value, 16)
    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }
}
