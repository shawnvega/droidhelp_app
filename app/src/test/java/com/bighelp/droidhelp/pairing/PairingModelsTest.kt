package com.bighelp.droidhelp.pairing

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingModelsTest {

    @Test
    fun `BeginPairingInput roundtrips through json`() = runTest {
        val input = BeginPairingInput(
            requestId = "request-1",
            deviceId = "device-1",
            timestamp = 1_700_000_000L,
        )

        val json = Json.encodeToString(BeginPairingInput.serializer(), input)
        val decoded = Json.decodeFromString(BeginPairingInput.serializer(), json)

        assertEquals(input, decoded)
    }

    @Test
    fun `ApprovePairingInput roundtrips through json`() = runTest {
        val input = ApprovePairingInput(
            requestId = "request-2",
            approved = true,
        )

        val json = Json.encodeToString(ApprovePairingInput.serializer(), input)
        val decoded = Json.decodeFromString(ApprovePairingInput.serializer(), json)

        assertEquals(input, decoded)
    }

    @Test
    fun `ClaimPairingInput roundtrips through json`() = runTest {
        val input = ClaimPairingInput(
            requestId = "request-3",
            claimCode = "claim-code-1",
        )

        val json = Json.encodeToString(ClaimPairingInput.serializer(), input)
        val decoded = Json.decodeFromString(ClaimPairingInput.serializer(), json)

        assertEquals(input, decoded)
    }

    @Test
    fun `InspectedPairing roundtrips through json`() = runTest {
        val input = InspectedPairing(
            pairingId = "pairing-1",
            inspector = "inspector-1",
            status = "pending",
        )

        val json = Json.encodeToString(InspectedPairing.serializer(), input)
        val decoded = Json.decodeFromString(InspectedPairing.serializer(), json)

        assertEquals(input, decoded)
    }
}
