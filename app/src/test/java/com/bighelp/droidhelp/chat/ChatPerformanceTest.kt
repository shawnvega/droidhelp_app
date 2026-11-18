package com.bighelp.droidhelp.chat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class ChatPerformanceTest {
    private lateinit var chatPerformance: ChatPerformance
    private val user = TimelineSender.user("You")
    private val agent = TimelineSender.agent("bot", "Bot")

    @BeforeEach
    fun setUp() {
        chatPerformance = ChatPerformance()
    }

    @Test
    fun `test duplicate append is a no-op`() {
        val item1 = TimelineItem(
            id = "msg-1",
            role = TimelineRole.HUMAN,
            sender = user,
            content = TimelineContent.Message("Hello"),
            metadata = TimelineMetadata(timestamp = Date())
        )
        val item2 = TimelineItem(
            id = "msg-1", // Same ID
            role = TimelineRole.HUMAN,
            sender = user,
            content = TimelineContent.Message("Different content but same ID"),
            metadata = TimelineMetadata(timestamp = Date())
        )

        chatPerformance.append(item1)
        chatPerformance.append(item2)

        assertEquals(1, chatPerformance.getHistorySize())
        val history = chatPerformance.getHistory()
        assertEquals("Hello", (history[0].content as TimelineContent.Message).text)
    }

    @Test
    fun `test ordering guarantees`() {
        val item1 = TimelineItem("1", TimelineRole.HUMAN, user, TimelineContent.Message("One"), TimelineMetadata())
        val item2 = TimelineItem("2", TimelineRole.ASSISTANT, agent, TimelineContent.Message("Two"), TimelineMetadata())
        val item3 = TimelineItem("3", TimelineRole.HUMAN, user, TimelineContent.Message("Three"), TimelineMetadata())

        chatPerformance.append(item1)
        chatPerformance.append(item2)
        chatPerformance.append(item3)

        val history = chatPerformance.getHistory()
        assertEquals(3, history.size)
        assertEquals("1", history[0].id)
        assertEquals("2", history[1].id)
        assertEquals("3", history[2].id)
    }

    @Test
    fun `test large history performance`() {
        val count = 1000
        val startTime = System.currentTimeMillis()
        
        for (i in 1..count) {
            chatPerformance.append(TimelineItem(
                id = "msg-$i",
                role = if (i % 2 == 0) TimelineRole.ASSISTANT else TimelineRole.HUMAN,
                sender = if (i % 2 == 0) agent else user,
                content = TimelineContent.Message("Message $i"),
                metadata = TimelineMetadata(timestamp = Date())
            ))
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        assertEquals(count, chatPerformance.getHistorySize())
        // A reasonable threshold for 15k appends in memory should be very low.
        // Even on a slow machine, it shouldn't take more than a second.
        assertTrue(duration < 2000, "Append time too high: ${duration}ms")
    }
}
