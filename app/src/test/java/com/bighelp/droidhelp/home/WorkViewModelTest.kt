package com.bighelp.droidhelp.home

import com.bighelp.droidhelp.home.model.WorkFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has empty items, ALL filter, and not loading`() {
        val viewModel = WorkViewModel()

        assertTrue(viewModel.items.value.isEmpty())
        assertEquals(WorkFilter.ALL, viewModel.filter.value)
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `setFilter updates active filter`() {
        val viewModel = WorkViewModel()

        viewModel.setFilter(WorkFilter.NEEDS_YOU)
        assertEquals(WorkFilter.NEEDS_YOU, viewModel.filter.value)

        viewModel.setFilter(WorkFilter.DONE)
        assertEquals(WorkFilter.DONE, viewModel.filter.value)
    }

    @Test
    fun `refresh completes and clears loading state`() = runTest(testDispatcher) {
        val viewModel = WorkViewModel()

        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.isLoading.value)
        assertTrue(viewModel.items.value.isEmpty())
    }
}
