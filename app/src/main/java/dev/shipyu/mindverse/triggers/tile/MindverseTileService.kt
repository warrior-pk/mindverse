package dev.shipyu.mindverse.triggers.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService
import dev.shipyu.mindverse.MainActivity

/**
 * Quick Settings trigger (ticket 05, M1 scope): tapping the tile opens the dashboard.
 * `startActivityAndCollapse(PendingIntent)` collapses the shade as part of the launch
 * (API 34+, matching minSdk). Voice-memo tile behavior arrives in M2.
 */
class MindverseTileService : TileService() {
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pending)
    }
}
