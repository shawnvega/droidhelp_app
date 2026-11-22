package com.bighelp.droidhelp.chat

import androidx.lifecycle.ViewModel
import com.bighelp.droidhelp.platform.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okhttp3.Response

class FakeHttpClient : HttpClient {
    override suspend fun post(url: String, body: Map<String, String>): Response = TODO()
    override suspend fun upload(url: String, name: String, fileName: String, mimeType: String, data: ByteArray, onProgress: (Float) -> Unit): Response = TODO()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onInputChange updates inputText`() = runTest {
        // We need a dummy HttpClient to satisfy the constructor
        val httpClient = object : HttpClient {
            override suspend fun post(url: String, body: Map<String, String>): Response = TODO()
            override suspend fun upload(url: String, name: String, fileName: String, mimeType: String, data: ByteArray, onProgress: (Float) -> Unit): Response = TODO()
        }
        val viewModel = ChatViewModel(httpClient)
        viewModel.onInputChange("hello")
        assertEquals("hello", viewModel.inputText.value)
    }

    @Test
    fun `send with blank input is a no-op`() = runTest {
        val httpClient = object : HttpClient {
            override suspend fun post(url: String, body: Map<String, String>): Response = TODO()
            override suspend fun upload(url: String, name: String, fileName: String, mimeType: String, data: ByteArray, onProgress: (Float) -> Unit): Response = TODO()
        }
        val viewModel = ChatViewModel(httpClient, onSendMessage = { _, _ -> })
        
        viewModel.onInputChange("   ")
        viewModel.send()
        
        assertEquals(0, viewModel.items.value.size)
        assertFalse(viewModel.isSending.value)
    }

    @Test
    fun `send appends message and clears input`() = runTest {
        val httpClient = object : HttpClient {
            override suspend fun post(url: String, body: Map<String, String>): Response = TODO()
            override suspend fun upload(url: String, name: String, fileName: String, mimeType: String, data: ByteArray, onProgress: (Float) -> Unit): Response = TODO()
        }
        val viewModel = ChatViewModel(httpClient)
        viewModel.onInputChange("Hello World")
        viewModel.send()
        
        assertEquals("", viewModel.inputText.value)
        assertEquals(1, viewModel.items.value.size)
        val item = viewModel.items.value[0]
        assertTrue(item.content is TimelineContent.Message)
        assertEquals("Hello World", (item.content as TimelineContent.Message).text)
        assertEquals(TimelineRole.HUMAN, item.role)
        assertEquals("user", item.sender.id)
    }

    @Test
    fun `send invokes onSendMessage`() = runTest {
        var sentText = ""
        val httpClient = object : HttpClient {
            override suspend fun post(url: String, body: Map<String, String>): Response = TODO()
            override suspend fun upload(url: String, name: String, fileName: String, mimeType: String, data: ByteArray, onProgress: (Float) -> Unit): Response = TODO()
        }
        val viewModel = ChatViewModel(httpClient, onSendMessage = { text, _ ->
            sentText = text
        })
        
        viewModel.onInputChange("Hello")
        viewModel.send()
        
        assertEquals("Hello", sentText)
    }
}
