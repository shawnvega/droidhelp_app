package com.bighelp.droidhelp.platform

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AndroidSecureStorage(
    context: Context
) : SecureStorage {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "secure_storage_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun saveString(key: String, value: String) {
        encryptedPrefs.edit().putString(key, value).apply()
    }

    override suspend fun getString(key: String): String? {
        return encryptedPrefs.getString(key, null)
    }

    override suspend fun delete(key: String) {
        encryptedPrefs.edit().remove(key).apply()
    }

    override suspend fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }

    override suspend fun saveByteArray(key: String, value: ByteArray) {
        val base64Encoded = android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
        encryptedPrefs.edit().putString(key, base64Encoded).apply()
    }

    override suspend fun getByteArray(key: String): ByteArray? {
        val base64Encoded = encryptedPrefs.getString(key, null) ?: return null
        return android.util.Base64.decode(base64Encoded, android.util.Base64.NO_WRAP)
    }
}
