package com.bighelp.droidhelp.session

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelsTest {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `session separates durableSessionId and volatile runtimeSessionId on construction`() {
        val detached = Session(
            id = "durable-Case-1",
            source = SessionCoordinator.SOURCE,
            profile = "research",
        )
        assertEquals("durable-Case-1", detached.id)
        assertEquals("durable-Case-1", detached.durableSessionId)
        assertNull(detached.runtimeSessionId)
        assertFalse(detached.hasRuntimeSessionId)

        val attached = Session.create(
            durableSessionId = "durable-Case-1",
            source = SessionCoordinator.SOURCE,
            profile = "research",
            runtimeSessionId = "runtime-Live-99",
        )
        assertEquals("durable-Case-1", attached.id)
        assertEquals("durable-Case-1", attached.durableSessionId)
        assertEquals("runtime-Live-99", attached.runtimeSessionId)
        assertTrue(attached.hasRuntimeSessionId)
        assertNotEquals(attached.durableSessionId, attached.runtimeSessionId)
    }

    @Test
    fun `session copy and mutation helpers keep durable and runtime IDs strictly independent`() {
        val initial = Session.create(
            durableSessionId = "durable-v1",
            source = SessionCoordinator.SOURCE,
            profile = "default",
            runtimeSessionId = "runtime-v1",
        )

        val reboundRuntime = initial.withRuntimeSessionId("runtime-v2")
        assertEquals("durable-v1", reboundRuntime.durableSessionId)
        assertEquals("durable-v1", reboundRuntime.id)
        assertEquals("runtime-v2", reboundRuntime.runtimeSessionId)

        val compactedDurable = reboundRuntime.withDurableSessionId("durable-v2")
        assertEquals("durable-v2", compactedDurable.durableSessionId)
        assertEquals("durable-v2", compactedDurable.id)
        assertEquals("runtime-v2", compactedDurable.runtimeSessionId)

        // Direct data class copy(id = ...) keeps durableSessionId in sync with id
        val copiedById = compactedDurable.copy(id = "durable-v3")
        assertEquals("durable-v3", copiedById.id)
        assertEquals("durable-v3", copiedById.durableSessionId)
        assertEquals("runtime-v2", copiedById.runtimeSessionId)

        val detached = copiedById.withoutRuntimeSessionId()
        assertEquals("durable-v3", detached.durableSessionId)
        assertNull(detached.runtimeSessionId)
        assertFalse(detached.hasRuntimeSessionId)
    }

    @Test
    fun `mapping between Session, StoredSession, RuntimeSession, and SessionIds preserves separation`() {
        val liveSession = Session.create(
            durableSessionId = "Saved-Session_42",
            source = SessionCoordinator.SOURCE,
            profile = "coding",
            runtimeSessionId = "Runtime-Ephemeral_7",
        )

        // Session -> StoredSession drops volatile runtimeSessionId
        val stored: StoredSession = liveSession.toStoredSession()
        assertEquals("Saved-Session_42", stored.durableSessionId)
        assertEquals(SessionCoordinator.SOURCE, stored.source)
        assertEquals("coding", stored.profile)

        // StoredSession -> Session defaults to detached (never copies durableSessionId into runtimeSessionId)
        val restoredDetached: Session = stored.toSession()
        assertEquals("Saved-Session_42", restoredDetached.id)
        assertEquals("Saved-Session_42", restoredDetached.durableSessionId)
        assertNull(restoredDetached.runtimeSessionId)
        assertNull(restoredDetached.toRuntimeSessionOrNull())

        // StoredSession -> RuntimeSession -> Session preserves both IDs without conflation
        val runtime: RuntimeSession = stored.toRuntimeSession("Runtime-Replacement_8")
        assertEquals("Runtime-Replacement_8", runtime.runtimeSessionId)
        assertEquals("Saved-Session_42", runtime.durableSessionId)
        assertEquals(stored, runtime.toStoredSession())

        val reboundSession: Session = runtime.toSession()
        assertEquals("Saved-Session_42", reboundSession.durableSessionId)
        assertEquals("Runtime-Replacement_8", reboundSession.runtimeSessionId)
        assertEquals(runtime, reboundSession.toRuntimeSessionOrNull())

        // SessionIds round-trip and detach/attach
        val ids = runtime.toSessionIds()
        assertEquals(reboundSession.toSessionIds(), ids)
        assertEquals("Saved-Session_42", ids.durableSessionId)
        assertEquals("Runtime-Replacement_8", ids.runtimeSessionId)
        assertTrue(ids.hasRuntimeSessionId)

        val detachedIds = ids.detachRuntime()
        assertEquals("Saved-Session_42", detachedIds.durableSessionId)
        assertNull(detachedIds.runtimeSessionId)
        assertFalse(detachedIds.hasRuntimeSessionId)

        val reattachedIds = detachedIds.attachRuntime("Runtime-Next_9")
        assertEquals("Saved-Session_42", reattachedIds.durableSessionId)
        assertEquals("Runtime-Next_9", reattachedIds.runtimeSessionId)
    }

    @Test
    fun `serialization never persists volatile runtimeSessionId and always decodes it as null`() {
        val attached = Session.create(
            durableSessionId = "durable-abc",
            source = SessionCoordinator.SOURCE,
            profile = "ops",
            runtimeSessionId = "volatile-xyz",
        )

        val sessionJson = Json.encodeToString(attached)
        val sessionObj = Json.parseToJsonElement(sessionJson).jsonObject
        assertEquals(JsonPrimitive("durable-abc"), sessionObj["id"])
        assertFalse("runtimeSessionId" in sessionObj)
        assertFalse(sessionJson.contains("volatile-xyz"))

        val decodedSession = Json.decodeFromString<Session>(sessionJson)
        assertEquals("durable-abc", decodedSession.durableSessionId)
        assertNull(decodedSession.runtimeSessionId)

        // Even if persisted JSON contains a stray runtimeSessionId field, decoding ignores it
        val pollutedJson = """{"id":"durable-abc","source":"bighelp","profile":"ops","runtimeSessionId":"stale-rt"}"""
        val decodedPolluted = lenientJson.decodeFromString<Session>(pollutedJson)
        assertEquals("durable-abc", decodedPolluted.durableSessionId)
        assertNull(decodedPolluted.runtimeSessionId)

        // StoredSession serializes explicit durableSessionId only
        val storedJson = Json.encodeToString(attached.toStoredSession())
        val storedObj = Json.parseToJsonElement(storedJson).jsonObject
        assertEquals(JsonPrimitive("durable-abc"), storedObj["durableSessionId"])
        assertFalse("runtimeSessionId" in storedObj)
        assertEquals(attached.toStoredSession(), Json.decodeFromString<StoredSession>(storedJson))

        // SessionIds serializes durableSessionId only
        val idsJson = Json.encodeToString(attached.toSessionIds())
        val idsObj = Json.parseToJsonElement(idsJson).jsonObject
        assertEquals(JsonPrimitive("durable-abc"), idsObj["durableSessionId"])
        assertFalse("runtimeSessionId" in idsObj)
        assertEquals(SessionIds(durableSessionId = "durable-abc"), Json.decodeFromString<SessionIds>(idsJson))
    }

    @Test
    fun `identifiers are preserved verbatim and blank IDs are rejected`() {
        val nfdDurable = "Cafe\u0301-Durable"
        val nfcRuntime = "Caf\u00E9-Runtime"
        val session = Session.create(
            durableSessionId = nfdDurable,
            source = SessionCoordinator.SOURCE,
            profile = "default",
            runtimeSessionId = nfcRuntime,
        )
        assertEquals(nfdDurable, session.durableSessionId)
        assertEquals(nfcRuntime, session.runtimeSessionId)

        assertThrows(IllegalArgumentException::class.java) {
            Session.create(durableSessionId = "   ", source = SessionCoordinator.SOURCE, profile = "default")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Session.create(
                durableSessionId = "valid-durable",
                source = SessionCoordinator.SOURCE,
                profile = "default",
                runtimeSessionId = "",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StoredSession(durableSessionId = "", source = SessionCoordinator.SOURCE, profile = "default")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeSession(
                runtimeSessionId = " ",
                durableSessionId = "valid-durable",
                source = SessionCoordinator.SOURCE,
                profile = "default",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionIds(durableSessionId = "valid-durable", runtimeSessionId = "\t")
        }
    }

    @Test
    fun `session coordinator preserves durable ID separation and validates blank runtimeSessionId`() = runTest {
        val repository = InMemorySessionRepository()
        val coordinator = SessionCoordinator(repository)

        val created = coordinator.create(source = "ignored", profile = "default")
        assertNotNull(created.durableSessionId)
        assertEquals(created.id, created.durableSessionId)
        assertNull(created.runtimeSessionId)

        val resumed = coordinator.resume(created.durableSessionId)
        assertEquals(created, resumed)

        val badRuntime = Session(
            id = "durable-with-blank-runtime",
            source = SessionCoordinator.SOURCE,
            profile = "default",
            runtimeSessionId = "   ",
        )
        repository.save(badRuntime)

        val ex = assertThrowsSuspend<SessionRuntimeException> {
            coordinator.resume("durable-with-blank-runtime")
        }
        assertTrue(ex.errors.any { it.contains("runtimeSessionId must not be blank") })
    }
}
