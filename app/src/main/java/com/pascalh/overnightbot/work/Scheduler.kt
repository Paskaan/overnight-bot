package com.pascalh.overnightbot.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pascalh.overnightbot.Graph
import com.pascalh.overnightbot.core.NY
import com.pascalh.overnightbot.core.Schedule
import com.pascalh.overnightbot.data.AndroidNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Wakes the bot up. Exact alarms hit the order windows; a 15-minute background check is the safety net
 * when an alarm is lost. Every wake-up runs the same "tick", which works out by itself what is due.
 */
object Scheduler {
    private const val TICK = "tick"
    private const val WATCHDOG = "watchdog"

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /** Sets the next alarm (or removes everything when the bot is off). Returns the alarm time or 0. */
    fun arm(ctx: Context): Long {
        Graph.init(ctx)
        val st = Graph.store.load()
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val work = WorkManager.getInstance(ctx)
        if (!st.settings.active) {
            alarms.cancel(intent)
            work.cancelUniqueWork(WATCHDOG)
            Graph.setNextAlarm(0L)
            return 0L
        }
        val at = Schedule.nextWake(ZonedDateTime.now(NY), st.settings.mode, st.calendar).toInstant().toEpochMilli()
        try {
            if (canExact(ctx)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
        Graph.setNextAlarm(at)
        val watchdog = PeriodicWorkRequestBuilder<TickWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("watchdog" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, watchdog)
        return at
    }

    /** Runs a tick as soon as there is a network connection. */
    fun runNow(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<TickWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(TICK, ExistingWorkPolicy.KEEP, request)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Graph.init(context)
        Scheduler.runNow(context)
        Scheduler.arm(context)
    }
}

/** After a restart, an app update or a clock change the alarm is gone and has to be set again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Graph.init(context)
        Scheduler.arm(context)
        if (Graph.store.load().settings.active) Scheduler.runNow(context)
    }
}

class TickWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Graph.init(applicationContext)
        val watchdog = inputData.getBoolean("watchdog", false)
        try {
            if (watchdog && !Schedule.mightBeDue(Graph.store.load(), ZonedDateTime.now(NY))) {
                Result.success()
            } else {
                Graph.setLastResult(Graph.runner.tick())
                Result.success()
            }
        } catch (e: IOException) {
            // No connection to Alpaca. The alarm run tries again; the watchdog simply comes back in 15 minutes.
            Graph.setLastResult("No connection to Alpaca: " + (e.message ?: "network error"))
            if (!watchdog && runAttemptCount < 10) Result.retry() else Result.success()
        } catch (e: Exception) {
            Graph.setLastResult("Error: " + (e.message ?: e.javaClass.simpleName))
            Result.failure()
        } finally {
            Scheduler.arm(applicationContext)
        }
    }

    /** Only used on Android 11 and older, where an urgent job runs as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        42,
        AndroidNotifier.build(applicationContext, "Overnight Bot", "Checking the market", false),
    )
}
