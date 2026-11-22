package com.bighelp.droidhelp.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Represents a single citation found in a chat message.
 *
 * @property index The numerical index of the citation (e.g., 1 for [1]).
 * @property url The URL associated with this citation.
 * @property title An optional title for the citation.
 * @property snippet An optional text snippet related to the citation.
 */
data class ChatCitation(
    val index: Int,
    val url: String,
    val title: String? = null,
    val snippet: String? = null
)

/**
 * Utility for parsing citations from chat message text.
 * Citations are expected to be in the format "[index] <url>" at the end of the message,
 * and referenced in the text as "[index]".
 */
object ChatCitations {
    // Matches markers like "[1]" in the text
    private val MARKER_PATTERN = java.util.regex.Pattern.compile("\\[(\\d+)\\]")

    // Matches definitions like "[1] https://example.com"
    // Following iOS pattern: ^ {0,3}(?:[-*+] )?\[([1-9][0-9]{0,5})\]:?[\\t ]+(.+)$
    private val DEFINITION_REGEX = Regex("^\\s*(?:[-*+]\\s+)?\\[(\\d+)\\]\\s*:?\\s*(.+)$")

    /**
     * Parses the provided text to extract citations.
     * 
     * @param text The full content of the assistant's message.
     * @return A sorted list of [ChatCitation] objects found in the text.
     */
    fun parse(text: String): List<ChatCitation> {
        val lines = text.lines()
        val definitions = mutableMapOf<Int, String>()
        
        for (line in lines) {
            val match = DEFINITION_REGEX.find(line.trim())
            if (match != null) {
                val index = match.groupValues[1].toInt()
                val url = match.groupValues[2].trim()
                definitions[index] = url
            }
        }

        val referencedIndices = mutableSetOf<Int>()
        val matcher = MARKER_PATTERN.matcher(text)
        while (matcher.find()) {
            referencedIndices.add(matcher.group(1).toInt())
        }

        val result = definitions.filterKeys { it in referencedIndices }
            .map { (index, url) ->
                // Find the first occurrence of the marker to extract a snippet
                val markerString = "[$index]"
                val markerIndex = text.indexOf(markerString)
                
                val snippet = if (markerIndex != -1) {
                    val start = (markerIndex - 50).coerceAtLeast(0)
                    val end = (markerIndex + markerString.length + 50).coerceAtMost(text.length)
                    text.substring(start, end).replace("\n", " ").trim()
                } else {
                    null
                }

                ChatCitation(
                    index = index,
                    url = url,
                    title = url, // Default title to URL
                    snippet = snippet
                )
            }
            .sortedBy { it.index }
        
        println("DEBUG: definitions=$definitions")
        println("DEBUG: referencedIndices=$referencedIndices")
        println("DEBUG: result=$result")
        
        return result
    }

    /**
     * Returns a regex pattern that matches the citation markers.
     */
    fun getMarkerPattern(): java.util.regex.Pattern = MARKER_PATTERN
}

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
        // Missing definition
        val text1 = """
            The sample garden opens at nine.[1]
        """.trimIndent()
        assertEquals(0, ChatCitations.parse(text1).size)

        // Malformed definition (missing URL)
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
        println("DEBUG: Citations found: $citations")
        
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
        println("DEBUG: Citations found: $citations")
        assertEquals(1, citations.size)
        assertEquals(1, citations[0].index)
    }
}
