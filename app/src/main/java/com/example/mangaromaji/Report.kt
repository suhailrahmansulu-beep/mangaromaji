package com.example.mangaromaji

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Main-thread report; stores no screenshot, Japanese dialogue, or API key. */
class Report(context: Context) {
    private val prefs=context.getSharedPreferences("diagnostics",Context.MODE_PRIVATE)
    private val events=ArrayDeque<String>()
    private var lastFailure=prefs.getString("failure", "None recorded") ?: "None recorded"
    private var state="STARTING"
    fun publish(code: String, detail: String, counters: String, failed: Boolean=false) {
        val time=SimpleDateFormat("HH:mm:ss",Locale.US).format(Date())
        state="$code: $detail"
        events.addLast("$time $state");while(events.size>24) events.removeFirst()
        if(failed) lastFailure="${BuildConfig.VERSION_NAME} $time $state"
        refresh(counters)
    }
    fun refresh(counters: String) {
        val report="Manga Romaji Preview ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n"+
            "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}\n$state\n$counters\n\nPRESERVED FAILURE:\n$lastFailure\n\n"+events.joinToString("\n")
        prefs.edit().putString("failure",lastFailure).putString("status",report).apply()
    }
}

object LatestReading { @Volatile var text="No reading yet." }
