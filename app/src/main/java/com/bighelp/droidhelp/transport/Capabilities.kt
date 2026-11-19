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
 *
 * Each capability may be associated with an owner (for example a plugin or an
 * owner token). Retiring an owner removes every capability registered under
 * that owner, so subsequent lookups return empty or raise a defined
 * [CapabilityNotFoundError].
 */
class CapabilitiesRegistry {

    private val lock = ReentrantReadWriteLock(true) // Fair lock for consistent ordering
    private val capabilities = mutableMapOf<String, CapabilityEntry>()

    /**
     * A registered capability together with its optional plugin context and owner.
     *
     * @property value The capability value, or null when present without a value.
     * @property context The optional plugin context attached at registration time.
     * @property owner The optional owner of this capability. When the owner is retired
     * via [retireOwner], all capabilities registered under that owner are removed.
     */
    data class CapabilityEntry(val value: String?, val context: String?, val owner: String? = null)

    /**
     * Thrown when a capability lookup fails because the key is unknown or its
     * owning owner has been retired.
     */
    class CapabilityNotFoundError(key: String) : IllegalStateException("Capability not found: $key")

    /**
     * Registers a capability under [key], replacing any previous value.
     *
     * @param key The stable capability key.
     * @param value The capability value, or null to mark the capability as present without a value.
     * @param context Optional plugin context attached to this capability. When omitted,
     * the capability is registered without a context and context-free callers are unaffected.
     * @param owner Optional owner of this capability. When the owner is retired via
     * [retireOwner], all capabilities registered under that owner are removed.
     */
    fun register(key: String, value: String? = null, context: String? = null, owner: String? = null) {
        lock.writeLock().withLock {
            capabilities[key] = CapabilityEntry(value, context, owner)
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
     * Looks up the owner of the capability registered under [key].
     *
     * @return The owner, or null when the key is unknown or no owner was attached.
     */
    fun lookupOwner(key: String): String? {
        return lock.readLock().withLock {
            capabilities[key]?.owner
        }
    }

    /**
     * Looks up the capability registered under [key], throwing [CapabilityNotFoundError]
     * when the key is unknown.
     *
     * @return The capability value.
     * @throws CapabilityNotFoundError when no capability is registered under [key].
     */
    fun lookupOrThrow(key: String): String {
        return lock.readLock().withLock {
            capabilities[key]?.value ?: throw CapabilityNotFoundError(key)
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
     * Retires an owner, removing every capability registered under that owner.
     *
     * @param owner The owner to retire (for example a plugin being unloaded or an
     * owner token being invalidated).
     * @return The number of capabilities removed.
     */
    fun retireOwner(owner: String): Int {
        return lock.writeLock().withLock {
            val removed = capabilities.filter { it.value.owner == owner }.keys
            removed.forEach { capabilities.remove(it) }
            removed.size
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
