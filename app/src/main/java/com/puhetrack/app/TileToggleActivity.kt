package com.puhetrack.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Non-exported user-action boundary for the Quick Settings tile. */
class TileToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PENDING, true).commit()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }

    companion object {
        private const val PREFS = "puhe_tile_request"
        private const val PENDING = "pending_toggle"

        fun consume(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pending = prefs.getBoolean(PENDING, false)
            if (pending) prefs.edit().remove(PENDING).commit()
            return pending
        }
    }
}
