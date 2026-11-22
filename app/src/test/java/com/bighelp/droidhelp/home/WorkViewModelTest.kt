package com.bighelp.droidhelp.home

import com.bighelp.droidhelp.home.model.WorkFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
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
