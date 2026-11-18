package com.bighelp.droidhelp.home.model

import java.util.*

enum class WorkKind {
    CODING, CHAT, BACKGROUND, WORKFLOW, TASK, SCHEDULE
}

enum class WorkBucket {
    NEEDS_YOU, WORKING, DONE, UPCOMING
}

enum class WorkFilter(val title: String) {
    ALL("All"),
    NEEDS_YOU("Needs you"),
    WORKING("Working"),
    DONE("Done")
}

sealed class WorkTarget {
    data class CodeSession(val id: String) : WorkTarget()
    data class CodeChanges(val id: String) : WorkTarget()
    data class CodeBatch(val id: UUID) : WorkTarget()
    data class Chat(val id: String) : WorkTarget()
    data class Bot(val id: String) : WorkTarget()
    data class WorkflowRun(val id: String) : WorkTarget()
    data class KanbanTask(val id: String) : WorkTarget()
    data class ScheduledTask(val identity: String) : WorkTarget()
}

enum class WorkTone {
    NEEDS_YOU, WORKING, DONE, FAILED, UPCOMING
}

data class WorkBatchLine(
    val id: String,
    val title: String,
    val index: Int,
    val status: String
)

sealed class WorkCard {
    data class Session(val record: Any, val status: String, val pullRequest: Any? = null) : WorkCard()
    data class Launch(val launch: Any) : WorkCard()
    data class Batch(val batch: Any, val lines: List<WorkBatchLine>, val startedCount: Int) : WorkCard()
    object Row : WorkCard()
}

data class WorkItem(
    val kind: WorkKind,
    val id: String,
    val title: String,
    val pill: String? = null,
    val line: String,
    val lineSymbol: String,
    val bot: String? = null,
    val time: Date? = null,
    val bucket: WorkBucket,
    val tone: WorkTone,
    val projectID: String? = null,
    val target: WorkTarget? = null,
    val card: WorkCard = WorkCard.Row
)

// Types for the iOS porting requirements

data class CodeSessionRecord(
    val id: String,
    val title: String,
    val projectName: String,
    val startedAt: Date,
    val projectID: String?
)

enum class CodeSessionStatus {
    NEEDS_YOU, STARTING, RUNNING, READY, FAILED
}

data class CodeLaunch(
    val id: UUID,
    val title: String,
    val sessionID: String?,
    val projectID: String?,
    val step: CodeSessionStatus
)

data class CodeBatch(
    val id: UUID,
    val title: String,
    val createdAt: Date,
    val projectID: String?
)

data class DashboardWorkItem(
    val sessionID: String,
    val title: String,
    val subtitle: String
)

data class BotBackgroundWork(
    val title: String,
    val count: Int
)

data class WorkflowRunSummary(
    val id: String,
    val workflowName: String,
    val stageCount: Int,
    val stagesDone: Int,
    val updatedAt: Date,
    val startedAt: Date,
    val endedAt: Date?
)

enum class KanbanLane {
    NEEDS_YOU, WORKING, DONE
}

data class HermesKanbanTask(
    val id: String,
    val title: String,
    val lane: KanbanLane?,
    val statusNote: String? = null,
    val assignee: String? = null,
    val completedAt: Date? = null,
    val lastHeartbeatAt: Date? = null,
    val startedAt: Date? = null,
    val createdAt: Date,
    val projectID: String? = null
)

enum class ScheduledStatus {
    PENDING, PAUSED, COMPLETED, FAILED
}

data class ScheduledTask(
    val identity: String,
    val name: String,
    val scheduleDescription: String,
    val nextRun: Date?,
    val lastRun: Date?,
    val status: ScheduledStatus,
    val agentID: String
)
