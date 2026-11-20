package com.bighelp.droidhelp.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CapabilitiesRegistryTest {

    @Test
    fun `register and lookup capability`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1")
        registry.register("key2", "value2")

        assertEquals("value1", registry.lookup("key1"))
        assertEquals("value2", registry.lookup("key2"))
        assertEquals(2, registry.size())
    }

    @Test
    fun `lookup non-existent key returns null`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1")

        assertNull(registry.lookup("non-existent"))
        assertEquals(1, registry.size())
    }

    @Test
    fun `list all keys`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1")
        registry.register("key2", "value2")

        val keys = registry.list()
        assertTrue(keys.contains("key1"))
        assertTrue(keys.contains("key2"))
        assertEquals(2, keys.size)
    }

    @Test
    fun `all returns map of all capabilities`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1")
        registry.register("key2", "value2")

        val all = registry.all()
        assertEquals("value1", all["key1"])
        assertEquals("value2", all["key2"])
        assertEquals(2, all.size)
    }

    @Test
    fun `unregister removes capability`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1")

        assertTrue(registry.unregister("key1"))
        assertNull(registry.lookup("key1"))
        assertEquals(0, registry.size())
    }

    @Test
    fun `clear removes all capabilities`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1")
        registry.register("key2", "value2")

        assertEquals(2, registry.size())
        registry.clear()
        assertEquals(0, registry.size())
        assertTrue(registry.list().isEmpty())
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `register with null value`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", null)

        assertNull(registry.lookup("key1"))
        assertEquals(1, registry.size())
    }

    @Test
    fun `register with plugin context and retrieve it`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1", context = "pluginA")

        assertEquals("value1", registry.lookup("key1"))
        assertEquals("pluginA", registry.lookupContext("key1"))
    }

    @Test
    fun `context-free registration and lookup still succeed`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1")

        assertEquals("value1", registry.lookup("key1"))
        assertNull(registry.lookupContext("key1"))
        assertEquals(1, registry.size())
    }

    @Test
    fun `register with context then overwrite without context clears context`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1", context = "pluginA")
        registry.register("key1", "value2") // no context provided

        assertEquals("value2", registry.lookup("key1"))
        assertNull(registry.lookupContext("key1"))
    }

    @Test
    fun `register with context then overwrite with different context`() {
        val registry = CapabilitiesRegistry()
        registry.register("key1", "value1", context = "pluginA")
        registry.register("key1", "value2", context = "pluginB")

        assertEquals("value2", registry.lookup("key1"))
        assertEquals("pluginB", registry.lookupContext("key1"))
    }

    @Test
    fun `lookupContext returns null for unknown key`() {
        val registry = CapabilitiesRegistry()
        assertNull(registry.lookupContext("unknown"))
    }

    @Test
    fun `retireOwner removes only capabilities registered under that owner`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", owner = "pluginA")
        registry.register("b", "v2", owner = "pluginB")
        registry.register("c", "v3", owner = "pluginA")
        registry.register("d", "v4") // no owner

        assertEquals(4, registry.size())
        assertEquals(2, registry.retireOwner("pluginA"))

        assertNull(registry.lookup("a"))
        assertNull(registry.lookup("c"))
        assertEquals("v2", registry.lookup("b"))
        assertEquals("v4", registry.lookup("d"))
        assertEquals(2, registry.size())
        assertTrue(registry.list().contains("b"))
        assertTrue(registry.list().contains("d"))
        assertFalse(registry.list().contains("a"))
        assertFalse(registry.list().contains("c"))
    }

    @Test
    fun `retireOwner returns zero for unknown owner`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", owner = "pluginA")

        assertEquals(0, registry.retireOwner("unknown"))
        assertEquals(1, registry.size())
        assertEquals("v1", registry.lookup("a"))
    }

    @Test
    fun `retireOwner removes context and owner of retired capabilities`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", context = "ctx", owner = "pluginA")

        assertEquals(1, registry.retireOwner("pluginA"))

        assertNull(registry.lookup("a"))
        assertNull(registry.lookupContext("a"))
        assertNull(registry.lookupOwner("a"))
    }

    @Test
    fun `lookupOrThrow returns value when present`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", owner = "pluginA")

        assertEquals("v1", registry.lookupOrThrow("a"))
    }

    @Test
    fun `lookupOrThrow throws CapabilityNotFoundError for unknown key`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", owner = "pluginA")

        var caught: Throwable? = null
        try {
            registry.lookupOrThrow("missing")
        } catch (ex: Throwable) {
            caught = ex
        }
        assertNotNull(caught)
        assertTrue(caught is CapabilitiesRegistry.CapabilityNotFoundError)
        assertTrue(caught!!.message!!.startsWith("Capability not found: "))
        assertTrue(caught!!.message!!.endsWith("missing"))
    }

    @Test
    fun `lookupOrThrow throws for capability retired with its owner`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", owner = "pluginA")

        registry.retireOwner("pluginA")

        var caught: Throwable? = null
        try {
            registry.lookupOrThrow("a")
        } catch (ex: Throwable) {
            caught = ex
        }
        assertNotNull(caught)
        assertTrue(caught is CapabilitiesRegistry.CapabilityNotFoundError)
        assertTrue(caught!!.message!!.startsWith("Capability not found: "))
        assertTrue(caught!!.message!!.endsWith("a"))
    }

    @Test
    fun `lookupOwner returns owner when present`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", owner = "pluginA")

        assertEquals("pluginA", registry.lookupOwner("a"))
    }

    @Test
    fun `lookupOwner returns null for unknown key`() {
        val registry = CapabilitiesRegistry()
        assertNull(registry.lookupOwner("unknown"))
    }

    @Test
    fun `lookupOwner returns null for ownerless capability`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1")

        assertNull(registry.lookupOwner("a"))
    }

    @Test
    fun `unregister returns false for unknown key`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1")

        assertFalse(registry.unregister("missing"))
        assertEquals(1, registry.size())
        assertEquals("v1", registry.lookup("a"))
    }

    @Test
    fun `contains distinguishes null-valued capability from unknown and retired keys`() {
        val registry = CapabilitiesRegistry()
        assertFalse(registry.contains("flag"))

        registry.register("flag", null, context = "pluginCtx", owner = "pluginA")
        assertTrue(registry.contains("flag"))
        assertNull(registry.lookup("flag"))
        assertEquals("pluginCtx", registry.lookupContext("flag"))
        assertEquals("pluginA", registry.lookupOwner("flag"))

        assertEquals(1, registry.retireOwner("pluginA"))
        assertFalse(registry.contains("flag"))
    }

    @Test
    fun `lookupEntry returns full entry atomically and null when absent or retired`() {
        val registry = CapabilitiesRegistry()
        assertNull(registry.lookupEntry("a"))

        registry.register("a", "v1", context = "ctxA", owner = "pluginA")
        assertEquals(
            CapabilitiesRegistry.CapabilityEntry(value = "v1", context = "ctxA", owner = "pluginA"),
            registry.lookupEntry("a")
        )

        registry.retireOwner("pluginA")
        assertNull(registry.lookupEntry("a"))
    }

    @Test
    fun `overwriting owned capability with new owner prevents old owner retirement from removing it`() {
        val registry = CapabilitiesRegistry()
        registry.register("shared", "v1", context = "ctxA", owner = "pluginA")
        registry.register("shared", "v2", context = "ctxB", owner = "pluginB")

        assertEquals(1, registry.size())
        assertEquals(0, registry.retireOwner("pluginA"))
        assertEquals("v2", registry.lookup("shared"))
        assertEquals("ctxB", registry.lookupContext("shared"))
        assertEquals("pluginB", registry.lookupOwner("shared"))

        assertEquals(1, registry.retireOwner("pluginB"))
        assertNull(registry.lookup("shared"))
        assertEquals(0, registry.size())
    }

    @Test
    fun `overwriting owned capability without owner clears ownership before old owner retirement`() {
        val registry = CapabilitiesRegistry()
        registry.register("k", "v1", owner = "pluginA")
        registry.register("k", "v2")

        assertNull(registry.lookupOwner("k"))
        assertEquals(0, registry.retireOwner("pluginA"))
        assertEquals("v2", registry.lookup("k"))
    }

    @Test
    fun `retireOwner is idempotent on repeated calls`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", owner = "pluginA")
        registry.register("b", "v2", owner = "pluginB")

        assertEquals(1, registry.retireOwner("pluginA"))
        assertEquals(0, registry.retireOwner("pluginA"))
        assertEquals(1, registry.size())
        assertEquals("v2", registry.lookup("b"))
    }

    @Test
    fun `capability can be re-registered after its owner was retired`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", context = "ctx1", owner = "pluginA")
        assertEquals(1, registry.retireOwner("pluginA"))
        assertFalse(registry.contains("a"))

        registry.register("a", "v2", context = "ctx2", owner = "pluginA")
        assertTrue(registry.contains("a"))
        assertEquals("v2", registry.lookup("a"))
        assertEquals("ctx2", registry.lookupContext("a"))
        assertEquals("pluginA", registry.lookupOwner("a"))
    }

    @Test
    fun `lookupOrThrow throws CapabilityNotFoundError with key for null-valued capability`() {
        val registry = CapabilitiesRegistry()
        registry.register("flag", null, owner = "pluginA")

        val ex = assertThrows(CapabilitiesRegistry.CapabilityNotFoundError::class.java) {
            registry.lookupOrThrow("flag")
        }
        assertEquals("flag", ex.key)
        assertEquals("Capability not found: flag", ex.message)
    }

    @Test
    fun `list and all return snapshot copies unaffected by subsequent mutations`() {
        val registry = CapabilitiesRegistry()
        registry.register("a", "v1", owner = "pluginA")
        registry.register("b", "v2", owner = "pluginB")

        val keysSnapshot = registry.list()
        val mapSnapshot = registry.all()

        registry.retireOwner("pluginA")
        registry.register("c", "v3")

        assertEquals(setOf("a", "b"), keysSnapshot)
        assertEquals(mapOf("a" to "v1", "b" to "v2"), mapSnapshot)
        assertEquals(setOf("b", "c"), registry.list())
        assertEquals(mapOf("b" to "v2", "c" to "v3"), registry.all())
    }

    @Test
    fun `concurrent register, lookup, and retireOwner remain thread-safe`() {
        val registry = CapabilitiesRegistry()
        val threadCount = 6
        val iterations = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)

        try {
            val tasks = (0 until threadCount).map { worker ->
                Callable {
                    assertTrue(startLatch.await(5, TimeUnit.SECONDS))
                    val owner = "owner-${worker % 3}"
                    for (i in 0 until iterations) {
                        val key = "cap-$worker-$i"
                        registry.register(key, "val-$i", context = "ctx-$worker", owner = owner)
                        registry.lookup(key)
                        registry.lookupContext(key)
                        registry.lookupOwner(key)
                        registry.lookupEntry(key)
                        registry.contains(key)
                        if (i % 10 == 0) {
                            registry.list()
                            registry.all()
                        }
                        if (i % 25 == 0 && worker % 3 == 0) {
                            registry.retireOwner("owner-0")
                        }
                    }
                    Unit
                }
            }

            val futures = tasks.map { executor.submit(it) }
            startLatch.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }

            // Retiring all workers' owners must leave the registry empty.
            registry.retireOwner("owner-0")
            registry.retireOwner("owner-1")
            registry.retireOwner("owner-2")
            assertEquals(0, registry.size())
            assertTrue(registry.list().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }
}
