package com.bighelp.droidhelp.platform

import com.bighelp.droidhelp.model.LoopdyLinkError
import com.bighelp.droidhelp.model.PairingApprovedUser
import com.bighelp.droidhelp.model.PairingPendingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Client for handling the pairing handshake and lifecycle operations.
 *
 * Implements the three pairing endpoints:
 * - beginPairing()   -> POST /api/pairing/begin
 * - approvePairing() -> POST /api/pairing/approve
 * - claimPairing()   -> POST /api/pairing/claim
 *
 * Uses OkHttp for networking and handles [LoopdyLinkError].
 */
class PairingClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaTypeOrNull()

    /**
     * Starts a new pairing session by creating a pending request on the server.
     */
    suspend fun beginPairing(): PairingPendingRequest = withContext(Dispatchers.IO) {
        val fullUrl = endpoint("begin")
        val request = Request.Builder()
            .url(fullUrl)
            .post("".toRequestBody(mediaType))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw LoopdyLinkError("HTTP error: ${response.code}")
                }
                val body = response.body?.string()
                    ?: throw LoopdyLinkError("Empty response body")
                try {
                    json.decodeFromString<PairingPendingRequest>(body)
                } catch (e: IllegalArgumentException) {
                    throw LoopdyLinkError("Failed to parse pairing response: ${e.message}", e)
                }
            }
        } catch (e: IOException) {
            throw LoopdyLinkError("Network error: ${e.message}", e)
        }
    }

    /**
     * Approves a specific pending pairing request for the given profile.
     *
     * @param request The pending request to approve.
     * @param profileID The profile ID under which this approval applies.
     */
    suspend fun approvePairing(
        request: PairingPendingRequest,
        profileID: String
    ): PairingApprovedUser = withContext(Dispatchers.IO) {
        val fullUrl = endpoint("approve")
        val payload = json.encodeToString(
            mapOf(
                "platform" to request.platform,
                "request_id" to request.requestID,
                "profile" to profileID
            )
        )
        val httpRequest = Request.Builder()
            .url(fullUrl)
            .post(payload.toRequestBody(mediaType))
            .build()

        try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw LoopdyLinkError("HTTP error: ${response.code}")
                }
                val body = response.body?.string()
                    ?: throw LoopdyLinkError("Empty response body")
                try {
                    json.decodeFromString<PairingApprovedUser>(body)
                } catch (e: IllegalArgumentException) {
                    throw LoopdyLinkError("Failed to parse approve response: ${e.message}", e)
                }
            }
        } catch (e: IOException) {
            throw LoopdyLinkError("Network error: ${e.message}", e)
        }
    }

    /**
     * Claims (completes) a pairing request, effectively finalizing it.
     *
     * @return The approved user associated with this claim.
     */
    suspend fun claimPairing(): PairingApprovedUser = withContext(Dispatchers.IO) {
        val fullUrl = endpoint("claim")
        val request = Request.Builder()
            .url(fullUrl)
            .post("".toRequestBody(mediaType))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw LoopdyLinkError("HTTP error: ${response.code}")
                }
                val body = response.body?.string()
                    ?: throw LoopdyLinkError("Empty response body")
                try {
                    json.decodeFromString<PairingApprovedUser>(body)
                } catch (e: IllegalArgumentException) {
                    throw LoopdyLinkError("Failed to parse claim response: ${e.message}", e)
                }
            }
        } catch (e: IOException) {
            throw LoopdyLinkError("Network error: ${e.message}", e)
        }
    }

    private fun endpoint(path: String): String {
        val cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl.removeSuffix("/") else baseUrl
        return "$cleanBaseUrl/api/pairing/$path"
    }
}
