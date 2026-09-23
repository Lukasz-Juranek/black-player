package com.lj.blackplayer

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.Player

/** Small persisted state: chosen folder, last track/position, shuffle and repeat. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun folder(c: Context): Uri? = sp(c).getString("folder", null)?.let(Uri::parse)
    fun setFolder(c: Context, uri: Uri) = sp(c).edit { putString("folder", uri.toString()) }

    fun lastTrack(c: Context): String? = sp(c).getString("track", null)
    fun lastPosition(c: Context): Long = sp(c).getLong("position", 0)
    fun setLast(c: Context, track: String?, position: Long) =
        sp(c).edit { putString("track", track); putLong("position", position) }

    fun shuffle(c: Context) = sp(c).getBoolean("shuffle", false)
    fun setShuffle(c: Context, on: Boolean) = sp(c).edit { putBoolean("shuffle", on) }

    fun repeat(c: Context) = sp(c).getInt("repeat", Player.REPEAT_MODE_OFF)
    fun setRepeat(c: Context, mode: Int) = sp(c).edit { putInt("repeat", mode) }
}
