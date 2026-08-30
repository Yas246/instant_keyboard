package helium314.keyboard.soundscore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MyInstantsSourceTest {
    private val html = javaClass.getResourceAsStream("/search_bruh.html")!!
        .readBytes().decodeToString()
    private val source = MyInstantsSource()

    @Test fun parseExtractsSoundsWithAbsoluteUrls() {
        val items = source.parse(html)
        assertEquals(3, items.size)
        val first = items.first()
        assertEquals("23010", first.id)
        assertEquals("BRUH", first.title)
        assertEquals("https://www.myinstants.com/media/sounds/movie_1.mp3", first.mediaUrl)
        assertEquals("https://www.myinstants.com/fr/instant/bruh/", first.pageUrl)
    }

    @Test fun parseEmptyPageReturnsEmptyList() {
        assertTrue(source.parse("<html><body></body></html>").isEmpty())
    }
}
