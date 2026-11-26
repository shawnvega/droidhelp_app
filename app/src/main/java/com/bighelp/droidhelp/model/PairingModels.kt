package com.bighelp.droidhelp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A pending pairing request returned by the server.
 *
 * @param platform The platform identifier (e.g., "android").
 * @param requestID The server-side request ID (16 hex chars when valid).
 * @param userID The user ID associated with the request.
 * @param userName The user's display name.
 * @param ageMinutes How long the request has been pending, in minutes.
 */
@Serializable
data class PairingPendingRequest(
    val platform: String,
    @SerialName("request_id")
    val requestID: String,
    @SerialName("user_id")
    val userID: String,
    @SerialName("user_name")
    val userName: String,
    @SerialName("age_minutes")
    val ageMinutes: Int
)

/**
 * An approved pairing user returned by the server.
 *
 * @param platform The platform identifier.
 * @param userID The user ID that was approved.
 * @param userName The user's display name.
 * @param approvedAt Unix epoch milliseconds when the user was approved, or null if unknown.
 */
@Serializable
data class PairingApprovedUser(
    val platform: String,
    @SerialName("user_id")
    val userID: String,
    @SerialName("user_name")
    val userName: String,
    @SerialName("approved_at")
    val approvedAt: Long?
)

/**
 * The full pairing catalog for a profile.
 *
 * @param profileID The profile ID this catalog belongs to.
 * @param pending Currently pending pairing requests.
 * @param approved Currently approved users.
 */
@Serializable
data class PairingCatalog(
    @SerialName("profile_id")
    val profileID: String,
    val pending: List<PairingPendingRequest>,
    val approved: List<PairingApprovedUser>
)
