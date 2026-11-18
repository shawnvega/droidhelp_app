package com.bighelp.droidhelp.transport

/**
 * Represents a message in the transport layer.
 */
sealed class Message {
    object Ping : Message()
    object Pong : Message()
    object Ready : Message()
    data class CapabilitiesRequest(val serverRequests: Boolean) : Message()
    data class CapabilitiesResponse(val capabilities: Map<String, String>) : Message()
}
