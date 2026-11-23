package com.bighelp.droidhelp.chat

import java.util.regex.Pattern

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
    private val MARKER_PATTERN = Pattern.compile("\\[(\\d+)\\]")

    private const val SNIPPET_PADDING = 50

    // Matches definitions like "[1] https://example.com"
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
        val bodyLines = mutableListOf<String>()
        
        for (line in lines) {
            val match = DEFINITION_REGEX.find(line.trim())
            if (match != null) {
                val index = match.groupValues[1].toInt()
                val url = match.groupValues[2].trim()
                definitions[index] = url
            } else {
                bodyLines.add(line)
            }
        }

        val bodyText = bodyLines.joinToString("\n")
        val referencedIndices = mutableSetOf<Int>()
        val matcher = MARKER_PATTERN.matcher(bodyText)
        while (matcher.find()) {
            referencedIndices.add(matcher.group(1).toInt())
        }

        val result = definitions.filterKeys { it in referencedIndices }
            .map { (index, url) ->
                // Find the first occurrence of the marker to extract a snippet
                val markerString = "[$index]"
                val markerIndex = bodyText.indexOf(markerString)
                
                val snippet = if (markerIndex != -1) {
                    val start = (markerIndex - SNIPPET_PADDING).coerceAtLeast(0)
                    val end = (markerIndex + markerString.length + SNIPPET_PADDING).coerceAtMost(bodyText.length)
                    bodyText.substring(start, end).replace("\n", " ").trim()
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

        return result
    }

    /**
     * Returns a regex pattern that matches the citation markers.
     */
    fun getMarkerPattern(): java.util.regex.Pattern = MARKER_PATTERN
}
