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
    
    // Session messages
    data class Prompt(val text: String, val entryId: String) : Message()
    data class Clarify(val question: String, val entryId: String) : Message()
    data class Approval(val message: String, val entryId: String) : Message()
    data class Receipt(val entryId: String) : Message()
}
