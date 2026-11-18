package com.bighelp.droidhelp.platform

interface SecureStorage {
    suspend fun saveString(key: String, value: String)
    suspend fun getString(key: String): String?
    suspend fun delete(key: String)
    suspend fun clearAll()
    suspend fun saveByteArray(key: String, value: ByteArray)
    suspend fun getByteArray(key: String): ByteArray?
}
