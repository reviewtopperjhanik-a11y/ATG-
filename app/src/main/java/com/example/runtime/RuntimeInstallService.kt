package com.example.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.AgtApplication
import com.example.MainActivity
import com.example.R
import com.example.domain.model.RuntimeInstallStage
import com.example.domain.model.RuntimeInstallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service (dataSync) keeping long-running runtime manifest download,
 * SHA-256 verification, and staging extraction user-visible and cancellable.
 */
class RuntimeInstallService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null
    private var terminalStageNotified: RuntimeInstallStage? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? AgtApplication
        if (app == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val repo = app.container.runtimeInstallerRepository
        val notificationManager = app.container.notificationManager

        if (intent?.action == ACTION_CANCEL) {
            repo.cancelInstall()
            stopForegroundCompat(removeNotification = true)
            stopSelf()
            return START_NOT_STICKY
        }

        val manifestUrl = intent?.getStringExtra(EXTRA_MANIFEST_URL)?.trim()?.ifBlank { null }
        val forceUpdate = intent?.getBooleanExtra(EXTRA_FORCE_UPDATE, false) ?: false
        terminalStageNotified = null

        val initialNotification = buildNotification(repo.installState.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observerJob?.cancel()
        observerJob = serviceScope.launch {
            repo.installState.collectLatest { state ->
                val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (state.isInstalling) {
                    mgr.notify(NOTIFICATION_ID, buildNotification(state))
                } else if (
                    state.stage in setOf(
                        RuntimeInstallStage.INSTALLED,
                        RuntimeInstallStage.FAILED,
                        RuntimeInstallStage.CANCELLED,
                    )
                ) {
                    if (state.stage != terminalStageNotified) {
                        terminalStageNotified = state.stage
                        when (state.stage) {
                            RuntimeInstallStage.INSTALLED -> {
                                notificationManager.notifyRuntimeEvent(
                                    eventKey = "linux-runtime-setup",
                                    title = getString(R.string.notif_runtime_ready_title),
                                    detail = state.statusDetail,
                                )
                            }
                            RuntimeInstallStage.FAILED -> {
                                notificationManager.notifyRuntimeEvent(
                                    eventKey = "linux-runtime-setup",
                                    title = getString(R.string.notif_runtime_failed_title),
                                    detail = state.errorMessage ?: state.statusDetail,
                                )
                            }
                            else -> Unit
                        }
                    }
                    stopForegroundCompat(removeNotification = true)
                    stopSelf()
                }
            }
        }

        repo.startInstall(manifestUrl, forceUpdate = forceUpdate)
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat(removeNotification: Boolean = false) {
        val flag = if (removeNotification) {
            ServiceCompat.STOP_FOREGROUND_REMOVE
        } else {
            ServiceCompat.STOP_FOREGROUND_DETACH
        }
        ServiceCompat.stopForeground(this, flag)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = mgr.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Linux Runtime Setup",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress for downloading, verifying, and installing the local Linux runtime."
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(state: RuntimeInstallState): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelIntent = Intent(this, RuntimeInstallService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AGT Code Linux Runtime")
            .setContentText(state.statusDetail)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isInstalling)

        if (state.isInstalling) {
            val pct = state.progressPercent
            if (pct != null) {
                builder.setProgress(100, pct, false)
            } else {
                builder.setProgress(0, 0, true)
            }
            builder.addAction(0, getString(R.string.setup_cancel_action), cancelPending)
        } else {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "agt_runtime_install"
        const val NOTIFICATION_ID = 4102
        const val ACTION_START = "com.example.runtime.ACTION_START_INSTALL"
        const val ACTION_CANCEL = "com.example.runtime.ACTION_CANCEL_INSTALL"
        const val EXTRA_MANIFEST_URL = "extra_manifest_url"
        const val EXTRA_FORCE_UPDATE = "extra_force_update"

        fun startInstallIntent(
            context: Context,
            manifestUrl: String? = null,
            forceUpdate: Boolean = false,
        ): Intent =
            Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_START
                if (!manifestUrl.isNullOrBlank()) {
                    putExtra(EXTRA_MANIFEST_URL, manifestUrl)
                }
                putExtra(EXTRA_FORCE_UPDATE, forceUpdate)
            }

        fun cancelInstallIntent(context: Context): Intent =
            Intent(context, RuntimeInstallService::class.java).apply {
                action = ACTION_CANCEL
            }
    }
}
