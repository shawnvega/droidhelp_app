package com.bighelp.droidhelp.transport

import kotlinx.serialization.Serializable

@Serializable
sealed class Message {
    @Serializable
    object Ping : Message()
    @Serializable
    object Pong : Message()
    @Serializable
    object Ready : Message()
    @Serializable
    data class CapabilitiesRequest(val serverRequests: Boolean) : Message()
    @Serializable
    data class CapabilitiesResponse(val capabilities: Map<String, String>) : Message()

    // Session messages
    @Serializable
    data class Prompt(val text: String, val entryId: String) : Message()
    @Serializable
    data class Clarify(val question: String, val entryId: String) : Message()
    @Serializable
    data class Approval(val message: String, val entryId: String) : Message()
    @Serializable
    data class Receipt(val entryId: String) : Message()
}