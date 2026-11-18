package com.bighelp.droidhelp.transport

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Heartbeat component that manages periodic pings and responds to incoming pings.
 *
 * @param transport The underlying transport to use for sending and receiving messages.
 * @param pingIntervalMillis The interval between pings.
 * @param timeoutTolerance A factor to multiply the pingInterval by to determine the silence timeout.
 * @param scope The coroutine scope used to launch the heartbeat loop.
 * @param currentTimeMillis A function that returns the current time in milliseconds.
 */
class Heartbeat(
    private val transport: Transport,
    private val pingIntervalMillis: Long,
    private val timeoutTolerance: Double = 2.0,
    private val scope: CoroutineScope,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var job: Job? = null
    private val lastSeenTimestamp = AtomicLong(0)
    private var isRunning = false

    var onTimeout: (() -> Unit)? = null

    init {
        transport.onMessage { message ->
            if (isRunning) {
                handleIncomingMessage(message)
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        lastSeenTimestamp.set(currentTimeMillis())
        transport.send(Message.Ready)

        job = scope.launch {
            while (isActive) {
                delay(pingIntervalMillis)
                transport.send(Message.Ping)
            }
        }

        // Launch a separate job to monitor timeout
        scope.launch {
            while (isRunning && isActive) {
                delay(pingIntervalMillis / 2) // Check more frequently than the ping interval
                checkTimeout()
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
    }

    private fun handleIncomingMessage(message: Message) {
        lastSeenTimestamp.set(currentTimeMillis())
        if (message is Message.Ping) {
            transport.send(Message.Pong)
        } else if (message is Message.Ready) {
            // Ready is acknowledged by updating lastSeenTimestamp
        } else {
            rejectUnsupported(transport, message)
        }
    }

    private fun checkTimeout() {
        val now = currentTimeMillis()
        val threshold = (pingIntervalMillis * timeoutTolerance).toLong()
        if (now - lastSeenTimestamp.get() > threshold) {
            onTimeout?.invoke()
        }
    }
}
