package com.bighelp.droidhelp.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import java.util.UUID

/**
 * Client for sending cancellation requests via HTTP.
 */
class CancellationClient(
    private val httpClient: OkHttpClient,
    private val endpoint: Endpoint
) {
    /**
     * Sends a cancellation request for the given [requestId].
     * @param requestId The unique identifier of the request to cancel.
     * @throws IOException if the request fails.
     */
    suspend fun cancel(requestId: String) = withContext(Dispatchers.IO) {
        val url = buildUrl()
        val cancellationId = UUID.randomUUID().toString()
        println("DEBUG: cancellationId = $cancellationId")
        val body = """{"requestId":"$requestId","cancellationId":"$cancellationId"}""".toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Cancellation request failed with code ${response.code}: ${response.message}")
            }
        }
    }

    private fun buildUrl(): String {
        val scheme = endpoint.scheme
        val formattedHost = if (endpoint.host.contains(':') && !endpoint.host.startsWith("[")) {
            "[${endpoint.host}]"
        } else {
            endpoint.host
        }
        val prefixSegment = endpoint.deploymentPrefix
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?.let { "/$it" } ?: ""
        val pathSegment = endpoint.path.trimEnd('/').let { if (it == "/") "" else it }
        val combinedPath = "$prefixSegment$pathSegment"
        val finalPath = if (combinedPath.endsWith("/")) {
            "${combinedPath.dropLast(1)}/api/cancel"
        } else {
            "$combinedPath/api/cancel"
        }
        return "$scheme://$formattedHost:${endpoint.port}$finalPath"
    }
}