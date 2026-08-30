// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import helium314.keyboard.soundscore.SoundItem

interface SoundsCallback {
    fun onSendSound(item: SoundItem)
    fun onSwitchToTextKeyboard()
}
