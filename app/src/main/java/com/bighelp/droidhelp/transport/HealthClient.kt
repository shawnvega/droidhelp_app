package com.bighelp.droidhelp.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

@Serializable
data class HealthResponse(
    val gateway: Gateway
)

@Serializable
data class Gateway(
    val capabilities: Capabilities
)

@Serializable
data class Capabilities(
    val per_session_exclusive_submit: Boolean? = null
)

class HealthClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getHealth(): HealthResponse = withContext(Dispatchers.IO) {
        val cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl.removeSuffix("/") else baseUrl
        val fullUrl = "$cleanBaseUrl/api/health"

        val request = Request.Builder()
            .url(fullUrl)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP error: ${response.code}")
            }

            val body = response.body?.string() 
                ?: throw IOException("Empty response body")
            
            try {
                val healthResponse = json.decodeFromString<HealthResponse>(body)
                if (healthResponse.gateway.capabilities.per_session_exclusive_submit != true) {
                    throw IOException("gateway.capabilities.per_session_exclusive_submit must be true")
                }
                healthResponse
            } catch (e: Exception) {
                if (e is IOException) throw e
                throw IOException("Failed to parse health response", e)
            }
        }
    }
}
