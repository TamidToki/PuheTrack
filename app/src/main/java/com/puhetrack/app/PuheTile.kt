package com.puhetrack.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class PuheTile : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, TileToggleActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launch = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    41,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                startActivityAndCollapse(intent)
            }
        }
        // Android will finish device authentication before delivering the explicit
        // toggle, avoiding a silent no-op from a locked Quick Settings surface.
        if (isLocked) unlockAndRun(launch) else launch()
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let {
            it.state = if (RecordTranslateService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = if (RecordTranslateService.running) "PuheTrack ●" else "PuheTrack"
            it.updateTile()
        }
    }
}
