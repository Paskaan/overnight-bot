package com.pascalh.overnightbot.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pascalh.overnightbot.R
import com.pascalh.overnightbot.core.Notifier
import com.pascalh.overnightbot.ui.MainActivity

/** Shows the bot's messages as phone notifications. */
class AndroidNotifier(private val ctx: Context) : Notifier {

    override fun notify(title: String, text: String, important: Boolean) {
        try {
            val manager = NotificationManagerCompat.from(ctx)
            if (manager.areNotificationsEnabled()) {
                manager.notify((System.currentTimeMillis() % 1_000_000_000L).toInt(), build(ctx, title, text, important))
            }
        } catch (e: SecurityException) {
            // Notification permission was taken away: the message is still in the log.
        }
    }

    companion object {
        const val ALERTS = "alerts"
        const val INFO = "info"

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(ALERTS, "Trades and alerts", NotificationManager.IMPORTANCE_HIGH)
            )
            nm.createNotificationChannel(
                NotificationChannel(INFO, "Daily report and quiet updates", NotificationManager.IMPORTANCE_LOW)
            )
        }

        fun build(ctx: Context, title: String, text: String, important: Boolean): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(ctx, if (important) ALERTS else INFO)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull() ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        }
    }
}
