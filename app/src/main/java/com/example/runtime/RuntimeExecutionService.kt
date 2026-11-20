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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service (dataSync) keeping long-running shell & runtime process execution
 * user-visible with an actionable Stop All notification control.
 */
class RuntimeExecutionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

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
        val executor = app.container.runtimeExecutor

        if (intent?.action == ACTION_STOP_ALL) {
            serviceScope.launch {
                executor.terminateAll()
                stopForegroundCompat()
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val initialCount = executor.activeProcessCount.value.coerceAtLeast(1)
        val initialNotification = buildNotification(initialCount)
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
            executor.activeProcessCount.collectLatest { count ->
                if (count <= 0) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    mgr.notify(NOTIFICATION_ID, buildNotification(count))
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = mgr.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Linux Process Execution",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows active Linux terminal and runtime commands running in AGT Code."
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(activeCount: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = Intent(this, RuntimeExecutionService::class.java).apply {
            action = ACTION_STOP_ALL
        }
        val stopPending = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = if (activeCount == 1) {
            "1 Linux command running"
        } else {
            "$activeCount Linux commands running"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AGT Code Runtime Execution")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, getString(R.string.terminal_stop_all), stopPending)
            .build()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "agt_runtime_execution"
        const val NOTIFICATION_ID = 4103
        const val ACTION_START = "com.example.runtime.ACTION_START_EXECUTION"
        const val ACTION_STOP_ALL = "com.example.runtime.ACTION_STOP_ALL_EXECUTION"

        fun startIntent(context: Context): Intent =
            Intent(context, RuntimeExecutionService::class.java).apply {
                action = ACTION_START
            }

        fun stopAllIntent(context: Context): Intent =
            Intent(context, RuntimeExecutionService::class.java).apply {
                action = ACTION_STOP_ALL
            }
    }
}
