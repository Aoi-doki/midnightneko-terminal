package dev.aoidoki.arise.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.aoidoki.arise.MainActivity
import dev.aoidoki.arise.R
import dev.aoidoki.arise.engine.SystemEvent

object Notifications {
    const val CH_SYSTEM = "system"
    const val CH_PENALTY = "penalty"
    const val CH_TRACKER = "tracker"
    const val CH_WORK = "work"

    const val ID_TRACKER = 1
    const val ID_WORK = 2
    private const val ID_EVENT_BASE = 100

    private const val BLUE = 0xFF1EA7FF.toInt()
    private const val RED = 0xFFFF2A3D.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SYSTEM, "System messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Quests arriving, level-ups, rank-ups and titles."
                },
                NotificationChannel(CH_PENALTY, "Penalty Zone", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Failed quests and the Penalty Quest. Not optional."
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 800)
                },
                NotificationChannel(CH_TRACKER, "The System is watching", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The live quest tracker. Keeps step counting alive."
                    setShowBadge(false)
                },
                NotificationChannel(CH_WORK, "Background work", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "AI quest writing and model downloads."
                    setShowBadge(false)
                },
            ),
        )
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun tracker(context: Context, title: String, text: String, penalty: Boolean, progress: Int?): Notification =
        NotificationCompat.Builder(context, CH_TRACKER)
            .setSmallIcon(R.drawable.ic_stat_system)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(if (penalty) RED else BLUE)
            .setColorized(penalty)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (progress != null) setProgress(100, progress, false) }
            .build()

    fun work(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_system)
            .setContentTitle("[SYSTEM]")
            .setContentText(text)
            .setColor(BLUE)
            .setOngoing(true)
            .setSilent(true)
            .build()

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun event(context: Context, e: SystemEvent) {
        if (!canPost(context)) return
        val penalty = e.type in setOf(SystemEvent.Type.PENALTY_STARTED, SystemEvent.Type.PENALTY_FAILED, SystemEvent.Type.DEATH, SystemEvent.Type.WARNING)
        val n = NotificationCompat.Builder(context, if (penalty) CH_PENALTY else CH_SYSTEM)
            .setSmallIcon(R.drawable.ic_stat_system)
            .setContentTitle("[SYSTEM] ${e.title}")
            .setContentText(e.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.message))
            .setColor(if (penalty) RED else BLUE)
            .setAutoCancel(true)
            .setCategory(if (penalty) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_EVENT_BASE + e.type.ordinal, n)
        } catch (_: SecurityException) {
        }
    }
}
