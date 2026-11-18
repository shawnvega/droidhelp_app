package com.bighelp.droidhelp.transport

/**
 * Represents an error response indicating that the requested method is not supported.
 */
data class UnsupportedMethod(val method: String) : Message()

/**
 * Creates an unsupported method error response for the given method name.
 */
fun unsupportedMethod(method: String): Message = UnsupportedMethod(method)

/**
 * Rejects an unsupported message by sending an [UnsupportedMethod] response.
 *
 * @param transport The transport to use for sending the error response.
 * @param message The unsupported message that triggered the rejection.
 */
fun rejectUnsupported(transport: Transport, message: Message) {
    val methodName = when (message) {
        is Message.Ping -> "Ping"
        is Message.Pong -> "Pong"
        is Message.Ready -> "Ready"
        is Message.CapabilitiesRequest -> "CapabilitiesRequest"
        is Message.CapabilitiesResponse -> "CapabilitiesResponse"
        is UnsupportedMethod -> "UnsupportedMethod"
        else -> "Unknown"
    }
    transport.send(unsupportedMethod(methodName))
}
