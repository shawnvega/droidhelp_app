package com.bighelp.droidhelp.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatCitationsTest {

    @Test
    fun testParseSingleCitation() {
        val text = """
            The sample garden opens at nine.[1]
            
            Sources
            [1] https://example.com/garden
        """.trimIndent()

        val citations = ChatCitations.parse(text)

        assertEquals(1, citations.size)
        assertEquals(1, citations[0].index)
        assertEquals("https://example.com/garden", citations[0].url)
    }

    @Test
    fun testParseMultipleCitations() {
        val text = """
            The sample garden opens at nine.[1]
            Bring water and comfortable shoes.[2]
            
            Sources
            [1] https://example.com/garden
            [2] https://example.com/walk
        """.trimIndent()

        val citations = ChatCitations.parse(text)

        assertEquals(2, citations.size)
        assertEquals(1, citations[0].index)
        assertEquals("https://example.com/garden", citations[0].url)
        assertEquals(2, citations[1].index)
        assertEquals("https://example.com/walk", citations[1].url)
    }

    @Test
    fun testParseMalformedCitations() {
        val text1 = """
            The sample garden opens at nine.[1]
        """.trimIndent()
        assertEquals(0, ChatCitations.parse(text1).size)

        val text2 = """
            The sample-garden opens at nine.[1]
            [1] 
        """.trimIndent()
        assertEquals(0, ChatCitations.parse(text2).size)
    }

    @Test
    fun testParseWithBulletPoints() {
        val text = """
            The sample garden opens at nine.[1]
            
            Sources
            * [1] https://example.com/garden
            - [2] https://example.com/walk
        """.trimIndent()

        val citations = ChatCitations.parse(text)

        assertEquals(1, citations.size)
        assertEquals(1, citations[0].index)
        assertEquals("https://example.com/garden", citations[0].url)
    }

    @Test
    fun testParseUnreferencedCitations() {
        val text = """
            The sample garden opens at nine.[1]
            
            Sources
            [1] https://example.com/garden
            [2] https://example.com/walk
        """.trimIndent()

        val citations = ChatCitations.parse(text)
        assertEquals(1, citations.size)
        assertEquals(1, citations[0].index)
    }
}
