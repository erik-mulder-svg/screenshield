package app.screenshield

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick-settings tile: tap to turn protection on/off. */
class ToggleTileService : TileService() {

    override fun onStartListening() = updateTile()

    override fun onClick() {
        if (CaptureService.running) {
            CaptureService.instance?.shutdown()
            updateTile()
        } else if (isLocked) {
            unlockAndRun { start() }
        } else {
            start()
        }
    }

    private fun start() {
        val intent = Intent(this, ProjectionRequestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val t = qsTile ?: return
        val on = CaptureService.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = if (on) "On" else "Off"
        t.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, ToggleTileService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
