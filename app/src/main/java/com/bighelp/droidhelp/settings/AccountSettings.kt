package com.bighelp.droidhelp.settings

import com.bighelp.droidhelp.platform.SecureStorage
import com.bighelp.droidhelp.transport.Endpoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class UserProfile(
    val name: String,
    val email: String? = null
)

class AccountSettings(private val storage: SecureStorage) {

    companion object {
        private const val KEY_ENDPOINT_URL = "account.endpoint_url"
        private const val KEY_PROFILE_JSON = "account.profile_json"
    }

    suspend fun getEndpointUrl(): String? {
        return storage.getString(KEY_ENDPOINT_URL)
    }

    suspend fun saveEndpointUrl(rawUrl: String): Endpoint {
        val endpoint = Endpoint.fromString(rawUrl.trim())
        storage.saveString(KEY_ENDPOINT_URL, endpoint.canonical)
        return endpoint
    }

    suspend fun getProfile(): UserProfile? {
        val json = storage.getString(KEY_PROFILE_JSON) ?: return null
        return Json.decodeFromString(json)
    }

    suspend fun saveProfile(profile: UserProfile) {
        storage.saveString(KEY_PROFILE_JSON, Json.encodeToString(profile))
    }

    suspend fun clear() {
        storage.delete(KEY_ENDPOINT_URL)
        storage.delete(KEY_PROFILE_JSON)
    }

    suspend fun signOut() {
        clear()
    }
}
