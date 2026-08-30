// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.soundscore

data class SoundItem(
    val id: String,
    val title: String,
    val mediaUrl: String,
    val pageUrl: String,
)

interface SoundSource {
    /** blocking — call from a background thread */
    fun search(query: String): List<SoundItem>
    /** blocking — call from a background thread */
    fun trending(): List<SoundItem>
}
