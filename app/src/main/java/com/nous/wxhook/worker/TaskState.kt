package com.nous.wxhook.worker

enum class TaskPhase {
    IDLE, PREPARING, RUNNING, VERIFYING, SUCCEEDED, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
    val isActive: Boolean get() = !isTerminal && this != IDLE
}

data class TaskState(
    val taskId: String = "",
    val type: TaskType = TaskType.BACKUP,
    val phase: TaskPhase = TaskPhase.IDLE,
    val progress: Float = 0f,
    val currentFile: String = "",
    val fileCount: Long = 0,
    val totalSize: Long = 0,
    val errorCode: String = "",
    val errorMessage: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
) {
    val durationMs: Long get() = if (startedAt > 0) (finishedAt ?: System.currentTimeMillis()) - startedAt else 0

    fun summary(): String = buildString {
        append("$type $phase")
        if (currentFile.isNotBlank()) append(" $currentFile")
        if (errorCode.isNotBlank()) append(" err=$errorCode")
    }
}

enum class TaskType { BACKUP, DECRYPT, SYNC }

sealed interface TaskEvent {
    data class Started(val taskId: String, val type: TaskType) : TaskEvent
    data class Progress(val taskId: String, val phase: TaskPhase, val progress: Float, val message: String = "") : TaskEvent
    data class Completed(val taskId: String, val success: Boolean, val message: String) : TaskEvent
    data class Failed(val taskId: String, val errorCode: String, val message: String) : TaskEvent
}
