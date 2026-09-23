package com.lj.blackplayer

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.Player

/** Small persisted state: music source, last track/position, shuffle and repeat. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)

    /** A picked folder's tree URI, or [Library.DOWNLOADS]. */
    fun source(c: Context): String? = sp(c).getString("folder", null)
    fun setSource(c: Context, source: String) = sp(c).edit { putString("folder", source) }

    fun lastTrack(c: Context): String? = sp(c).getString("track", null)
    fun lastPosition(c: Context): Long = sp(c).getLong("position", 0)
    fun setLast(c: Context, track: String?, position: Long) =
        sp(c).edit { putString("track", track); putLong("position", position) }

    fun shuffle(c: Context) = sp(c).getBoolean("shuffle", false)
    fun setShuffle(c: Context, on: Boolean) = sp(c).edit { putBoolean("shuffle", on) }

    fun repeat(c: Context) = sp(c).getInt("repeat", Player.REPEAT_MODE_OFF)
    fun setRepeat(c: Context, mode: Int) = sp(c).edit { putInt("repeat", mode) }
}
