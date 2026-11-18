package com.bighelp.droidhelp.platform

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureStorage(
    private val context: Context,
    private val keyStore: KeyStore? = null,
    private val keyAlias: String = "hermes_secure_storage"
) : SecureStorage {

    private val sharedPreferences = context.getSharedPreferences("secure_storage_prefs", Context.MODE_PRIVATE)

    private val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private val TRANSFORMATION = "AES/GCM/NoPadding"
    private val IV_SIZE = 12 // GCM standard IV size

    override suspend fun saveString(key: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        saveByteArray(key, bytes)
    }

    override suspend fun getString(key: String): String? {
        val bytes = getByteArray(key) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    override suspend fun delete(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }

    override suspend fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }

    override suspend fun saveByteArray(key: String, value: ByteArray) {
        val encryptedData = encrypt(value)
        val base64Encoded = Base64.encodeToString(encryptedData, Base64.NO_WRAP)
        sharedPreferences.edit().putString(key, base64Encoded).apply()
    }

    override suspend fun getByteArray(key: String): ByteArray? {
        val base64Encoded = sharedPreferences.getString(key, null) ?: return null
        val encryptedData = Base64.decode(base64Encoded, Base64.NO_WRAP)
        return try {
            decrypt(encryptedData)
        } catch (e: Exception) {
            null
        }
    }

    private fun encrypt(data: ByteArray): ByteArray {
        checkVersion()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)

        return ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(encryptedDataWithIv: ByteArray): ByteArray {
        checkVersion()
        if (encryptedDataWithIv.size < IV_SIZE) {
            throw IllegalArgumentException("Invalid encrypted data length")
        }

        val buffer = ByteBuffer.wrap(encryptedDataWithIv)
        val iv = ByteArray(IV_SIZE)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        checkVersion()
        val ks = getOrLoadKeyStore()
        val existingKey = ks.getKey(keyAlias, null) as? SecretKey
        if (existingKey != null) return existingKey

        if (ks.provider.name == KEYSTORE_PROVIDER) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()

            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        } else {
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(128)
            val key = keyGenerator.generateKey()
            // ks.setKeyEntry(keyAlias, key, null)
            return key
        }
    }

    private fun getOrLoadKeyStore(): KeyStore {
        return keyStore ?: KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)
        }
    }

    private fun checkVersion() {
        if (Build.VERSION.SDK_INT < 23) {
            throw IllegalStateException("AndroidKeyStore AES path requires API 23 or higher")
        }
    }
}
