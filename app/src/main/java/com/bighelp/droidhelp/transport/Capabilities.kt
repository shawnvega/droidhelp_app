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
 *
 * An optional plugin context can be attached to a capability at registration
 * time and retrieved later. Context-free registration and lookup remain fully
 * backward compatible.
 */
class CapabilitiesRegistry {

    private val lock = ReentrantReadWriteLock(true) // Fair lock for consistent ordering
    private val capabilities = mutableMapOf<String, CapabilityEntry>()

    /**
     * A registered capability together with its optional plugin context.
     *
     * @property value The capability value, or null when present without a value.
     * @property context The optional plugin context attached at registration time.
     */
    data class CapabilityEntry(val value: String?, val context: String?)

    /**
     * Registers a capability under [key], replacing any previous value.
     *
     * @param key The stable capability key.
     * @param value The capability value, or null to mark the capability as present without a value.
     * @param context Optional plugin context attached to this capability. When omitted,
     * the capability is registered without a context and context-free callers are unaffected.
     */
    fun register(key: String, value: String? = null, context: String? = null) {
        lock.writeLock().withLock {
            capabilities[key] = CapabilityEntry(value, context)
        }
    }

    /**
     * Looks up the capability registered under [key].
     *
     * @return The capability value, or null when the key is unknown.
     */
    fun lookup(key: String): String? {
        return lock.readLock().withLock {
            capabilities[key]?.value
        }
    }

    /**
     * Looks up the plugin context attached to the capability registered under [key].
     *
     * @return The plugin context, or null when the key is unknown or no context was attached.
     */
    fun lookupContext(key: String): String? {
        return lock.readLock().withLock {
            capabilities[key]?.context
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
            capabilities.mapValues { it.value.value }
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
