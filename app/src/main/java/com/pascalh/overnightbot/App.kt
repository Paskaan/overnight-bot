package com.pascalh.overnightbot

import android.app.Application
import android.content.Context
import com.pascalh.overnightbot.core.AlpacaBroker
import com.pascalh.overnightbot.core.Broker
import com.pascalh.overnightbot.core.FileStore
import com.pascalh.overnightbot.core.Runner
import com.pascalh.overnightbot.core.Settings
import com.pascalh.overnightbot.core.State
import com.pascalh.overnightbot.core.UrlHttp
import com.pascalh.overnightbot.data.AndroidNotifier
import com.pascalh.overnightbot.data.Creds
import com.pascalh.overnightbot.work.Scheduler
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        AndroidNotifier.createChannels(this)
        Scheduler.arm(this)
    }
}

/** The few long-lived objects of the app. */
object Graph {
    private var ready = false
    lateinit var appContext: Context
        private set
    lateinit var store: FileStore
        private set
    lateinit var creds: Creds
        private set
    lateinit var runner: Runner
        private set
    val http = UrlHttp()

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        appContext = ctx.applicationContext
        store = FileStore(File(appContext.filesDir, "bot"))
        creds = Creds(appContext)
        runner = Runner(store, { broker(it) }, AndroidNotifier(appContext))
        ready = true
    }

    /** Alpaca client for the mode in the settings, or null when no keys are saved for it. */
    fun broker(s: Settings): Broker? {
        val keys = creds.get(s.mode) ?: return null
        return AlpacaBroker(http, keys.first, keys.second, s.mode)
    }

    /** Changes the saved state. Waits while a bot run is in progress so nothing is overwritten. */
    fun update(change: (State) -> State): State = synchronized(runner) {
        val st = change(store.load())
        store.save(st)
        st
    }

    private fun prefs() = appContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    fun lastResult(): String = prefs().getString("lastResult", "") ?: ""

    fun setLastResult(text: String) {
        prefs().edit().putString("lastResult", text).apply()
    }

    fun nextAlarm(): Long = prefs().getLong("nextAlarm", 0L)

    fun setNextAlarm(at: Long) {
        prefs().edit().putLong("nextAlarm", at).apply()
    }
}
