package com.bighelp.droidhelp.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory repository that mirrors the production [SessionRepository] interface.
 */
class InMemorySessionRepository : SessionRepository {
    private val sessions = mutableMapOf<String, Session>()

    override suspend fun save(session: Session) {
        sessions[session.id] = session
    }

    override suspend fun findById(id: String): Session? {
        return sessions[id]
    }

    fun insertWithDifferentKey(session: Session, key: String) {
        sessions[key] = session
    }

    fun clear() {
        sessions.clear()
    }
}

class SessionCoordinatorTest {

    private lateinit var repository: InMemorySessionRepository
    private lateinit var coordinator: SessionCoordinator

    @Before
    fun setUp() {
        repository = InMemorySessionRepository()
        coordinator = SessionCoordinator(repository)
    }

    // ------------------------------------------------------------------
    // create()
    // ------------------------------------------------------------------

    @Test
    fun `create sets source and profile correctly`() = runTest {
        val source = "bighelp"
        val profile = "test-profile"
        val session = coordinator.create(source, profile)

        assertEquals(source, session.source)
        assertEquals(profile, session.profile)
        assertNotNull(session.id)

        val retrieved = repository.findById(session.id)
        assertEquals(session, retrieved)
    }

    @Test
    fun `create forces source to bighelp even when a different source is passed`() = runTest {
        val session = coordinator.create("desktop", "my-profile")
        assertEquals(SessionCoordinator.SOURCE, session.source)
        assertEquals("my-profile", session.profile)
    }

    // ------------------------------------------------------------------
    // resume — exact ID round-trip
    // ------------------------------------------------------------------

    @Test
    fun `resume round-trips exact ID including unusual characters`() = runTest {
        val unusualId = "AbC-123_XyZ.!@#%^&*()[]{}|;:'\",./<>?-"
        val session = Session(id = unusualId, source = SessionCoordinator.SOURCE, profile = "test-profile")
        repository.save(session)

        val resumed = coordinator.resume(unusualId)
        assertEquals(unusualId, resumed.id)
        assertEquals(session, resumed)
    }

    @Test
    fun `resume is case sensitive and does not normalize the ID`() = runTest {
        val storedId = "CaseSensitive-ID"
        repository.save(Session(id = storedId, source = SessionCoordinator.SOURCE, profile = "test-profile"))

        val resumed = coordinator.resume(storedId)
        assertEquals(storedId, resumed.id)

        assertThrows<SessionNotFoundException> {
            coordinator.resume("casesensitive-id")
        }
    }

    // ------------------------------------------------------------------
    // resume — unknown ID
    // ------------------------------------------------------------------

    @Test
    fun `resume with unknown ID throws SessionNotFoundException`() = runTest {
        assertThrows<SessionNotFoundException> {
            coordinator.resume("non-existent-id")
        }
    }

    // ------------------------------------------------------------------
    // resume — stored validation failure (ID mismatch)
    // ------------------------------------------------------------------

    @Test
    fun `resume with ID mismatch throws SessionStoredException`() = runTest {
        val realId = "real-id"
        val wrongId = "wrong-id"
        val session = Session(id = realId, source = SessionCoordinator.SOURCE, profile = "test-profile")

        repository.insertWithDifferentKey(session, wrongId)

        val exception = assertThrows<SessionStoredException> {
            coordinator.resume(wrongId)
        }
        assertTrue(exception.errors.any { it.contains("Stored validation: ID mismatch") })
    }

    // ------------------------------------------------------------------
    // resume — runtime validation failure (source)
    // ------------------------------------------------------------------

    @Test
    fun `resume with invalid runtime source throws SessionRuntimeException`() = runTest {
        val id = "session-1"
        val session = Session(id = id, source = "wrong-source", profile = "test-profile")
        repository.save(session)

        val exception = assertThrows<SessionRuntimeException> {
            coordinator.resume(id)
        }
        assertTrue(exception.errors.any { it.contains("Runtime validation: source must be 'bighelp'") })
    }

    // ------------------------------------------------------------------
    // resume — blank profile trips BOTH runtime and stored checks
    // ------------------------------------------------------------------

    @Test
    fun `resume with blank profile reports both runtime and stored failures`() = runTest {
        val id = "session-1"
        val session = Session(id = id, source = SessionCoordinator.SOURCE, profile = " ")
        repository.save(session)

        val exception = assertThrows<SessionValidationException> {
            coordinator.resume(id)
        }
        assertTrue(exception.runtimeErrors.any { it.contains("Runtime validation: profile must not be blank") })
        assertTrue(exception.storedErrors.any { it.contains("Stored validation: profile field missing") })
    }

    // ------------------------------------------------------------------
    // resume — both stored and runtime errors report both
    // ------------------------------------------------------------------

    @Test
    fun `resume with both stored and runtime errors reports both`() = runTest {
        val requestedId = "requested-id"
        val actualId = "actual-id"
        val session = Session(id = actualId, source = "wrong-source", profile = "test-profile")

        repository.insertWithDifferentKey(session, requestedId)

        val exception = assertThrows<SessionValidationException> {
            coordinator.resume(requestedId)
        }

        val errorString = (exception.runtimeErrors + exception.storedErrors).joinToString("; ")
        assertTrue(errorString.contains("Stored validation: ID mismatch"))
        assertTrue(errorString.contains("Runtime validation: source must be 'bighelp'"))
    }
}
