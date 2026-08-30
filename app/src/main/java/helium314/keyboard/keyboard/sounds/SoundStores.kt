// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.Context
import helium314.keyboard.soundscore.SoundStore

/** single process-wide SoundStore instance — SoundStore snapshots the whole file on save */
object SoundStores {
    @Volatile private var instance: SoundStore? = null
    fun get(context: Context): SoundStore =
        instance ?: synchronized(this) {
            instance ?: SoundStore(
                java.io.File(context.applicationContext.filesDir, "sounds_store.properties")
            ).also { instance = it }
        }
}
