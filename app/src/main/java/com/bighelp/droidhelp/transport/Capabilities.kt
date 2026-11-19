package com.bighelp.droidhelp.transport

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Thread-safe registry of capabilities exposed by a peer or host.
 *
 * Capabilities are identified by a stable string key and carry an optional
 * string value (for example a protocol version or feature flag). All reads and
 * writes are guarded by a fair read/write lock so the registry can safely be
 * shared across threads.
 */
class CapabilitiesRegistry {

    private val lock = ReentrantReadWriteLock(true) // Fair lock for consistent ordering
    private val capabilities = mutableMapOf<String, String?>()

    /**
     * Registers a capability under [key], replacing any previous value.
     *
     * @param key The stable capability key.
     * @param value The capability value, or null to mark the capability as present without a value.
     */
    fun register(key: String, value: String? = null) {
        lock.writeLock().withLock {
            capabilities[key] = value
        }
    }

    /**
     * Looks up the capability registered under [key].
     *
     * @return The capability value, or null when the key is unknown.
     */
    fun lookup(key: String): String? {
        return lock.readLock().withLock {
            capabilities[key]
        }
    }

    /**
     * Lists all registered capability keys.
     */
    fun list(): Set<String> {
        return lock.readLock().withLock {
            capabilities.keys.toSet()
        }
    }

    /**
     * Returns all registered capabilities as a key to value map.
     */
    fun all(): Map<String, String?> {
        return lock.readLock().withLock {
            capabilities.toMap()
        }
    }

    /**
     * Removes the capability registered under [key].
     *
     * @return true when a capability was removed, false when the key was unknown.
     */
    fun unregister(key: String): Boolean {
        return lock.writeLock().withLock {
            capabilities.remove(key) != null
        }
    }

    /**
     * Removes all registered capabilities.
     */
    fun clear() {
        lock.writeLock().withLock {
            capabilities.clear()
        }
    }

    /**
     * Returns the number of registered capabilities.
     */
    fun size(): Int {
        return lock.readLock().withLock {
            capabilities.size
        }
    }
}