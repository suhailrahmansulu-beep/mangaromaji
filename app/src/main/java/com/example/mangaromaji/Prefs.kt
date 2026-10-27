package com.example.mangaromaji

import android.app.Application
import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 0 = romaji, 1 = romaji + English, 2 = English only */
    fun mode(c: Context) = sp(c).getInt("mode", 0)
    fun setMode(c: Context, v: Int) = sp(c).edit().putInt("mode", v).apply()

    fun macrons(c: Context) = sp(c).getBoolean("macrons", true)
    fun setMacrons(c: Context, v: Boolean) = sp(c).edit().putBoolean("macrons", v).apply()

    /** seconds; 0 = never auto-hide */
    fun hideSec(c: Context) = sp(c).getInt("hide", 12)
    fun setHideSec(c: Context, v: Int) = sp(c).edit().putInt("hide", v).apply()

    fun lastStop(c: Context): String = sp(c).getString("stop", "") ?: ""
    fun setLastStop(c: Context, s: String) = sp(c).edit().putString("stop", s).apply()
}

/** Records crashes so the app can tell you why it stopped. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Prefs.setLastStop(this, "Crashed: ${e.javaClass.simpleName} ${e.message?.take(80) ?: ""}") }
            prev?.uncaughtException(t, e)
        }
    }
}
