// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.soundscore

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

class SoundStore(private val file: File) {
    private val props = Properties().also { p ->
        if (file.exists()) FileInputStream(file).use { p.load(it) }
    }
    private val favorites = LinkedHashMap<String, SoundItem>()
    private val recents = LinkedHashMap<String, SoundItem>()

    init {
        listOf("favorites" to favorites, "recents" to recents).forEach { (key, map) ->
            idsOf(key).forEach { id -> load(id)?.let { map[id] = it } }
        }
    }

    fun favorites(): List<SoundItem> = favorites.values.toList()

    fun isFavorite(id: String): Boolean = favorites.containsKey(id)

    fun toggleFavorite(item: SoundItem) {
        if (favorites.remove(item.id) == null) favorites[item.id] = item
        save()
    }

    fun addRecent(item: SoundItem) {
        recents.remove(item.id)
        recents[item.id] = item
        while (recents.size > MAX_RECENTS) recents.remove(recents.keys.first())
        save()
    }

    fun recents(): List<SoundItem> = recents.values.reversed().toList()

    private fun idsOf(key: String): List<String> =
        (props.getProperty(key) ?: "").split("\n").filter { it.isNotBlank() }

    private fun load(id: String): SoundItem? {
        val media = props.getProperty("item.$id.media") ?: return null
        return SoundItem(id,
            props.getProperty("item.$id.title") ?: "",
            media,
            props.getProperty("item.$id.page") ?: "")
    }

    private fun save() {
        props.setProperty("favorites", favorites.keys.joinToString("\n"))
        props.setProperty("recents", recents.keys.joinToString("\n"))
        (favorites.values + recents.values).forEach {
            props.setProperty("item.${it.id}.title", it.title.replace(Regex("[\\p{Cntrl}]"), " "))
            props.setProperty("item.${it.id}.media", it.mediaUrl)
            props.setProperty("item.${it.id}.page", it.pageUrl)
        }
        FileOutputStream(file).use { props.store(it, "soundboard store") }
    }

    private companion object { const val MAX_RECENTS = 20 }
}
