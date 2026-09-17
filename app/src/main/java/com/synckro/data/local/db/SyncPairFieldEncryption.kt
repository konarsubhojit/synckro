package com.synckro.data.local.db

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.synckro.data.local.entity.SyncPairEntity
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface StringFieldCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String
}

object SyncPairFieldEncryption {
    private const val PREFIX = "enc:v1:"

    @Volatile
    private var cipher: StringFieldCipher = PlainStringFieldCipher

    fun configure(context: Context) {
        cipher =
            if (Build.FINGERPRINT == "robolectric") {
                PlainStringFieldCipher
            } else {
                AndroidKeyStoreStringFieldCipher(context.applicationContext)
            }
    }

    fun encrypt(value: String): String = if (isEncrypted(value)) value else PREFIX + cipher.encrypt(value)

    fun decrypt(value: String): String = if (isEncrypted(value)) cipher.decrypt(value.removePrefix(PREFIX)) else value

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(pair: SyncPairEntity): SyncPairEntity =
        pair.copy(
            localTreeUri = encrypt(pair.localTreeUri),
            remoteFolderId = encrypt(pair.remoteFolderId),
            remoteFolderName = pair.remoteFolderName?.let(::encrypt),
            lastDeltaToken = pair.lastDeltaToken?.let(::encrypt),
        )

    fun decrypt(pair: SyncPairEntity): SyncPairEntity =
        pair.copy(
            localTreeUri = decrypt(pair.localTreeUri),
            remoteFolderId = decrypt(pair.remoteFolderId),
            remoteFolderName = pair.remoteFolderName?.let(::decrypt),
            lastDeltaToken = pair.lastDeltaToken?.let(::decrypt),
        )

    fun useCipherForTesting(testCipher: StringFieldCipher) {
        cipher = testCipher
    }

    fun resetForTesting() {
        cipher = PlainStringFieldCipher
    }
}

private object PlainStringFieldCipher : StringFieldCipher {
    override fun encrypt(plaintext: String): String = plaintext

    override fun decrypt(ciphertext: String): String = ciphertext
}

private class AndroidKeyStoreStringFieldCipher(
    @Suppress("unused") private val context: Context,
) : StringFieldCipher {
    private val key: SecretKey
        get() {
            val keyStore =
                KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                    load(null)
                }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            keyGenerator.init(
                KeyGenParameterSpec
                    .Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return keyGenerator.generateKey()
        }

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "synckro_sync_pair_field_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
