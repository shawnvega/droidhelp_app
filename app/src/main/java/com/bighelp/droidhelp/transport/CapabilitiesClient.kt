package com.bighelp.droidhelp.transport

/**
 * Client for advertising and handling capabilities.
 *
 * @param transport The underlying transport to use for sending and receiving messages.
 */
class CapabilitiesClient(
    private val transport: Transport
) {
    /**
     * Starts the client: registers handlers and advertises capabilities.
     * Call this on every new socket connection (initial connect and reconnects).
     */
    fun start() {
        registerHandlers()
        advertise()
    }

    /**
     * Registers the handler for `client.capabilities` requests from the server.
     */
    fun registerHandlers() {
        transport.onMessage { message ->
            if (message is Message.CapabilitiesRequest) {
                handleCapabilitiesRequest(message)
            } else {
                rejectUnsupported(transport, message)
            }
        }
    }

    /**
     * Advertises the client's capabilities to the server.
     */
    fun advertise() {
        val capabilities = mapOf(
            "server_requests" to "true",
            "protocol_version" to "1.0"
        )
        transport.send(Message.CapabilitiesResponse(capabilities))
    }

    private fun handleCapabilitiesRequest(request: Message.CapabilitiesRequest) {
        // In a real implementation, we might check request.serverRequests
        // but for now we just respond with our capabilities.
        advertise()
    }
}
