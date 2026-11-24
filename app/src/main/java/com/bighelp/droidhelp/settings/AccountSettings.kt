package com.bighelp.droidhelp.settings

import com.bighelp.droidhelp.platform.SecureStorage
import com.bighelp.droidhelp.transport.Endpoint

class AccountSettings(private val storage: SecureStorage) {

    companion object {
        private const val KEY_ENDPOINT_URL = "account.endpoint_url"
    }

    suspend fun getEndpointUrl(): String? {
        return storage.getString(KEY_ENDPOINT_URL)
    }

    suspend fun saveEndpointUrl(rawUrl: String): Endpoint {
        val endpoint = Endpoint.fromString(rawUrl.trim())
        storage.saveString(KEY_ENDPOINT_URL, endpoint.canonical)
        return endpoint
    }

    suspend fun clear() {
        storage.delete(KEY_ENDPOINT_URL)
    }
}
