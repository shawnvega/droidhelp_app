package com.bighelp.droidhelp.model

/**
 * Error thrown by the pairing client when the LoopdyLink pairing handshake fails.
 *
 * Wraps the original cause so the exception chain is preserved.
 */
class LoopdyLinkError(message: String?, cause: Throwable? = null) : Exception(message, cause)
