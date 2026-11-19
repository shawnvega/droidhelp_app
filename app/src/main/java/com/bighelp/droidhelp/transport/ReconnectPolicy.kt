package com.bighelp.droidhelp.transport

import com.bighelp.droidhelp.session.Session
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Policy for handling reconnection attempts and session resumption.
 */
object ReconnectPolicy {
    private val delays = listOf(
        1.seconds,
        2.seconds,
        4.seconds,
        8.seconds,
        15.seconds,
        30.seconds
    )
    private const val quietAttempts = 3

    private var currentSession: Session? = null

    /** Sets the current session to be resumed upon reconnection. */
    fun setSession(session: Session?) {
        currentSession = session
    }

    /** Gets the current session. */
    fun getSession(): Session? = currentSession

    /** Returns the delay for the given attempt number. */
    fun delay(afterAttempt: Int): Duration {
        val index = afterAttempt.coerceIn(0, delays.size - 1)
        return delays[index]
    }

    /** Returns true if the number of attempts is within the 'quiet' window. */
    fun isQuiet(attempt: Int): Boolean {
        return attempt < quietAttempts
    }
}
