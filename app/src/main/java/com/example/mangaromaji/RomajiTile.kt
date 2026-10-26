package com.example.mangaromaji

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Quick Settings toggle. */
class RomajiTile : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val running = OverlayService.instance
        if (running != null) {
            running.stopSelf()
            OverlayService.running = false
            refresh()
            return
        }
        val target = if (Settings.canDrawOverlays(this)) CaptureActivity::class.java else MainActivity::class.java
        val i = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else launchOld(i)
    }

    @Suppress("DEPRECATION")
    private fun launchOld(i: Intent) = startActivityAndCollapse(i)

    private fun refresh() {
        qsTile?.let {
            it.state = if (OverlayService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }
}
