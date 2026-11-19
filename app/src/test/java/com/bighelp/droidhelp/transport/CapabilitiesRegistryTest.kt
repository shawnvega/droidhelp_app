package com.bighelp.droidhelp.transport

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

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
}