package com.bighelp.droidhelp.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bighelp.droidhelp.home.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.*

class WorkViewModel : ViewModel() {
    private val _items = MutableStateFlow<List<WorkItem>>(emptyList())
    val items: StateFlow<List<WorkItem>> = _items.asStateFlow()

    private val _filter = MutableStateFlow(WorkFilter.ALL)
    val filter: StateFlow<WorkFilter> = _filter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // In a real app, we would trigger an initial load here.
        // For this task, we'll assume the data is provided or loaded via refresh.
    }

    fun setFilter(newFilter: WorkFilter) {
        _filter.value = newFilter
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Simulate loading work
                // In a real implementation, this would call a repository
                loadWork()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadWork() {
        // This is where the actual logic to fetch and project items would go.
        // For now, we leave it empty as the view will be implemented.
    }
}
