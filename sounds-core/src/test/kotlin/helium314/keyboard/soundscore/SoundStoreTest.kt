package helium314.keyboard.soundscore

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundStoreTest {
    private fun item(id: String) = SoundItem(id, "titre $id", "https://x/$id.mp3", "https://x/$id")

    @Test fun toggleFavoriteAddsThenRemoves() {
        val store = SoundStore(File.createTempFile("store", ".properties"))
        store.toggleFavorite(item("1"))
        assertTrue(store.isFavorite("1"))
        assertEquals(1, store.favorites().size)
        store.toggleFavorite(item("1"))
        assertFalse(store.isFavorite("1"))
        assertEquals(0, store.favorites().size)
    }

    @Test fun addRecentDedupsAndLimitsTo20() {
        val store = SoundStore(File.createTempFile("store", ".properties"))
        (1..25).forEach { store.addRecent(item("$it")) }
        store.addRecent(item("25")) // déjà en tête
        val recents = store.recents()
        assertEquals(20, recents.size)
        assertEquals("25", recents.first().id)
        assertEquals("6", recents.last().id)
    }

    @Test fun persistsAcrossInstances() {
        val file = File.createTempFile("store", ".properties")
        val store = SoundStore(file)
        store.toggleFavorite(item("42"))
        store.addRecent(item("7"))
        val reloaded = SoundStore(file)
        assertTrue(reloaded.isFavorite("42"))
        assertEquals("7", reloaded.recents().single().id)
        assertEquals("titre 7", reloaded.recents().single().title)
    }
}
