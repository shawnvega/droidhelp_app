package com.bighelp.droidhelp.transport

/**
 * Interface for the underlying transport mechanism.
 */
interface Transport {
    fun send(message: Message)
    fun onMessage(callback: (Message) -> Unit)
}
