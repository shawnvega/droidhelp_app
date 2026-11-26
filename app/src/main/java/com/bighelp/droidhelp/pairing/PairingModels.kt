package com.bighelp.droidhelp.pairing

import kotlinx.serialization.Serializable

@Serializable
data class BeginPairingInput(
    val requestId: String,
    val deviceId: String,
    val timestamp: Long,
) {
    init {
        require(requestId.isNotEmpty()) { "requestId must not be empty" }
        require(deviceId.isNotEmpty()) { "deviceId must not be empty" }
        require(timestamp >= 0L) { "timestamp must be non-negative: $timestamp" }
    }
}

@Serializable
data class ApprovePairingInput(
    val requestId: String,
    val approved: Boolean,
) {
    init {
        require(requestId.isNotEmpty()) { "requestId must not be empty" }
    }
}

@Serializable
data class ClaimPairingInput(
    val requestId: String,
    val claimCode: String,
) {
    init {
        require(requestId.isNotEmpty()) { "requestId must not be empty" }
        require(claimCode.isNotEmpty()) { "claimCode must not be empty" }
    }
}

@Serializable
data class InspectedPairing(
    val pairingId: String,
    val inspector: String,
    val status: String,
) {
    init {
        require(pairingId.isNotEmpty()) { "pairingId must not be empty" }
        require(inspector.isNotEmpty()) { "inspector must not be empty" }
        require(status.isNotEmpty()) { "status must not be empty" }
    }
}
