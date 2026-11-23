package com.bighelp.droidhelp.home.model

data class TodayNeedsYouSummary(val count: Int = 0, val summary: String = "")

data class TodayPendingShares(val count: Int = 0)

enum class TodayBoardKind {
    FEED, IDEAS, GOALS
}

data class TodayBoardEntry(
    val id: String,
    val kind: TodayBoardKind,
    val title: String,
    val preview: String = ""
)

enum class TodayPanelSize {
    COMPACT, FULL
}

data class TodayPanelDescriptor(
    val id: String,
    val title: String,
    val size: TodayPanelSize,
    val isVisible: Boolean = true,
    val order: Int = 0
)

data class TodayPanelLayout(val panels: List<TodayPanelDescriptor>) {
    fun visiblePanels(): List<TodayPanelDescriptor> {
        return panels.filter { it.isVisible }.sortedBy { it.order }
    }
}

data class TodayUiState(
    val needsYou: TodayNeedsYouSummary = TodayNeedsYouSummary(),
    val pendingShares: TodayPendingShares = TodayPendingShares(),
    val boards: List<TodayBoardEntry> = emptyList(),
    val layout: TodayPanelLayout = TodayPanelLayout(emptyList())
)
