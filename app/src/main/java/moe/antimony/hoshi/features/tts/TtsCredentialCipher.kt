package moe.antimony.hoshi.features.tts

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher

/** The non-exportable Keystore key stays on this device; DataStore holds ciphertext only. */
@Singleton
class TtsCredentialCipher @Inject constructor(@param:IoDispatcher private val io: CoroutineDispatcher) {
    private val alias = "hoshi.tts.credentials"

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    suspend fun encrypt(value: String, source: String): String = withContext(io) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(source.toByteArray())
        Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()))
    }

    suspend fun decrypt(value: String, source: String): String = withContext(io) {
        try {
            val bytes = Base64.getDecoder().decode(value)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(source.toByteArray())
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw TtsException(TtsFailure.Configuration) }
    }
}
