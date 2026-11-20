package com.example.core.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.security.SecretRedactor
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.TaskExecutionSummary
import com.example.domain.model.TaskState
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * Central Android alert dispatcher honoring the four persisted notification preferences in
 * [AppPreferencesRepository] (`notifyTaskComplete`, `notifyTaskFailure`, `notifyApprovals`,
 * `notifyRuntimeEvents`), Android 13+ POST_NOTIFICATIONS runtime permission, and secret redaction.
 */
class AgtNotificationManager(
    context: Context,
    private val preferencesRepository: AppPreferencesRepository,
    private val knownSecretsProvider: () -> List<String> = { emptyList() },
) {
    private val appContext = context.applicationContext
    private val notificationManagerCompat = NotificationManagerCompat.from(appContext)
    private val activeApprovalIdsByTask = ConcurrentHashMap<String, MutableSet<String>>()

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val systemManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (systemManager.getNotificationChannel(CHANNEL_TASK_ALERTS) == null) {
            val taskChannel = NotificationChannel(
                CHANNEL_TASK_ALERTS,
                appContext.getString(R.string.notif_channel_tasks_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = appContext.getString(R.string.notif_channel_tasks_desc)
            }
            systemManager.createNotificationChannel(taskChannel)
        }
        if (systemManager.getNotificationChannel(CHANNEL_RUNTIME_ALERTS) == null) {
            val runtimeChannel = NotificationChannel(
                CHANNEL_RUNTIME_ALERTS,
                appContext.getString(R.string.notif_channel_runtime_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.notif_channel_runtime_desc)
            }
            systemManager.createNotificationChannel(runtimeChannel)
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun canPostNotifications(): Boolean {
        if (!hasNotificationPermission()) return false
        return notificationManagerCompat.areNotificationsEnabled()
    }

    suspend fun notifyTaskResult(
        projectId: String,
        projectName: String,
        summary: TaskExecutionSummary,
    ) {
        cancelApprovalNotificationsForTask(summary.taskId)
        val prefs = preferencesRepository.preferences.first()
        when (summary.finalState) {
            TaskState.COMPLETED -> {
                if (!prefs.notifyTaskComplete) return
                val safeProject = redactAndBound(projectName.ifBlank { "Workspace" }, 48)
                val filesSummary = if (summary.filesChanged.isNotEmpty()) {
                    " · ${summary.filesChanged.size} file(s) updated"
                } else {
                    ""
                }
                val body = redactAndBound(
                    "${summary.objective}$filesSummary",
                    200,
                )
                postNotification(
                    channelId = CHANNEL_TASK_ALERTS,
                    tag = taskResultTag(summary.taskId),
                    notificationId = notificationIdFor("task-result-${summary.taskId}"),
                    title = appContext.getString(R.string.notif_task_complete_title, safeProject),
                    body = body,
                    projectId = projectId,
                    priority = NotificationCompat.PRIORITY_HIGH,
                    autoCancel = true,
                )
            }
            TaskState.FAILED -> {
                if (!prefs.notifyTaskFailure) return
                val safeProject = redactAndBound(projectName.ifBlank { "Workspace" }, 48)
                val reason = summary.unresolvedIssues.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: summary.objective
                val body = redactAndBound(reason, 200)
                postNotification(
                    channelId = CHANNEL_TASK_ALERTS,
                    tag = taskResultTag(summary.taskId),
                    notificationId = notificationIdFor("task-result-${summary.taskId}"),
                    title = appContext.getString(R.string.notif_task_failed_title, safeProject),
                    body = body,
                    projectId = projectId,
                    priority = NotificationCompat.PRIORITY_HIGH,
                    autoCancel = true,
                )
            }
            else -> Unit
        }
    }

    suspend fun notifyPlanApprovalRequired(
        projectId: String,
        projectName: String,
        taskId: String,
        objective: String,
    ) {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.notifyApprovals) return
        val safeProject = redactAndBound(projectName.ifBlank { "Workspace" }, 48)
        val body = redactAndBound(objective, 180)
        postNotification(
            channelId = CHANNEL_TASK_ALERTS,
            tag = planApprovalTag(taskId),
            notificationId = notificationIdFor("plan-approval-$taskId"),
            title = appContext.getString(R.string.notif_plan_approval_title, safeProject),
            body = body,
            projectId = projectId,
            priority = NotificationCompat.PRIORITY_HIGH,
            autoCancel = false,
        )
    }

    suspend fun notifyToolApprovalRequired(
        projectId: String,
        projectName: String,
        taskId: String,
        approvalId: String,
        title: String,
        reason: String,
    ) {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.notifyApprovals) return
        activeApprovalIdsByTask.computeIfAbsent(taskId) { ConcurrentHashMap.newKeySet() }.add(approvalId)
        val safeProject = redactAndBound(projectName.ifBlank { "Workspace" }, 48)
        val detailText = buildString {
            append(title.trim())
            if (reason.isNotBlank()) {
                if (isNotEmpty()) append(": ")
                append(reason.trim())
            }
        }.ifBlank { "User approval required before continuing." }
        postNotification(
            channelId = CHANNEL_TASK_ALERTS,
            tag = toolApprovalTag(approvalId),
            notificationId = notificationIdFor("tool-approval-$approvalId"),
            title = appContext.getString(R.string.notif_tool_approval_title, safeProject),
            body = redactAndBound(detailText, 200),
            projectId = projectId,
            priority = NotificationCompat.PRIORITY_HIGH,
            autoCancel = false,
        )
    }

    fun cancelPlanApprovalNotification(taskId: String) {
        notificationManagerCompat.cancel(planApprovalTag(taskId), notificationIdFor("plan-approval-$taskId"))
    }

    fun cancelToolApprovalNotification(approvalId: String) {
        notificationManagerCompat.cancel(toolApprovalTag(approvalId), notificationIdFor("tool-approval-$approvalId"))
        activeApprovalIdsByTask.values.forEach { it.remove(approvalId) }
    }

    fun cancelApprovalNotificationsForTask(taskId: String) {
        cancelPlanApprovalNotification(taskId)
        val tracked = activeApprovalIdsByTask.remove(taskId).orEmpty()
        tracked.forEach { approvalId ->
            notificationManagerCompat.cancel(toolApprovalTag(approvalId), notificationIdFor("tool-approval-$approvalId"))
        }
    }

    suspend fun notifyRuntimeEvent(
        eventKey: String,
        title: String,
        detail: String,
    ) {
        val prefs = preferencesRepository.preferences.first()
        if (!prefs.notifyRuntimeEvents) return
        postNotification(
            channelId = CHANNEL_RUNTIME_ALERTS,
            tag = runtimeEventTag(eventKey),
            notificationId = notificationIdFor("runtime-$eventKey"),
            title = redactAndBound(title, 80),
            body = redactAndBound(detail, 200),
            projectId = null,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            autoCancel = true,
        )
    }

    @SuppressLint("MissingPermission")
    private fun postNotification(
        channelId: String,
        tag: String,
        notificationId: Int,
        title: String,
        body: String,
        projectId: String?,
        priority: Int,
        autoCancel: Boolean,
    ) {
        if (!canPostNotifications()) return
        ensureChannels()

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!projectId.isNullOrBlank()) {
                putExtra(MainActivity.EXTRA_PROJECT_ID, projectId)
            }
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setPriority(priority)
            .setAutoCancel(autoCancel)
            .build()

        runCatching {
            notificationManagerCompat.notify(tag, notificationId, notification)
        }
    }

    private fun redactAndBound(raw: String, maxChars: Int): String {
        val redacted = SecretRedactor.redact(raw, knownSecretsProvider())
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (redacted.length <= maxChars) {
            redacted
        } else {
            redacted.take(maxChars - 1).trimEnd() + "…"
        }
    }

    private fun notificationIdFor(key: String): Int =
        4200 + (key.hashCode() and 0x0FFF_FFFF) % 50000

    private fun taskResultTag(taskId: String): String = "agt-task-$taskId"
    private fun planApprovalTag(taskId: String): String = "agt-plan-approval-$taskId"
    private fun toolApprovalTag(approvalId: String): String = "agt-tool-approval-$approvalId"
    private fun runtimeEventTag(eventKey: String): String = "agt-runtime-$eventKey"

    companion object {
        const val CHANNEL_TASK_ALERTS = "agt_task_alerts"
        const val CHANNEL_RUNTIME_ALERTS = "agt_runtime_alerts"
    }
}
