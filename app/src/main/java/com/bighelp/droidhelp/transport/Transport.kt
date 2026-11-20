package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session

/**
 * Interface for the underlying transport mechanism.
 */
interface Transport {
    suspend fun connect(): Boolean
    fun send(message: Message)
    fun onMessage(callback: (Message) -> Unit)
    fun resumeSession(session: Session)
}
