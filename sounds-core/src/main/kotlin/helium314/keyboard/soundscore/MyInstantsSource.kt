// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.soundscore

import org.jsoup.Jsoup
import java.net.URLEncoder

class MyInstantsSource(
    private val baseUrl: String = "https://www.myinstants.com",
) : SoundSource {

    override fun search(query: String): List<SoundItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("$baseUrl/fr/search/?name=$encoded")
            .userAgent(USER_AGENT).timeout(TIMEOUT_MS).get()
        return parse(doc)
    }

    // racine seule : /fr/ n'existe plus (404) ; la racine redirige vers la page géolocalisée
    // (ex. /en/index/bj/) qui contient les mêmes div.instant
    override fun trending(): List<SoundItem> {
        val doc = Jsoup.connect("$baseUrl/").userAgent(USER_AGENT).timeout(TIMEOUT_MS).get()
        return parse(doc)
    }

    fun parse(html: String): List<SoundItem> = parse(Jsoup.parse(html))

    fun parse(doc: org.jsoup.nodes.Document): List<SoundItem> = doc.select("div.instant").mapNotNull { el ->
        val play = el.selectFirst("button.small-button")?.attr("onclick") ?: return@mapNotNull null
        val media = PLAY_REGEX.find(play)?.groupValues?.get(1) ?: return@mapNotNull null
        val link = el.selectFirst("a.instant-link") ?: return@mapNotNull null
        val id = ID_REGEX.find(play)?.groupValues?.get(1) ?: media.hashCode().toString()
        SoundItem(id, link.text().trim(), absolutize(media), absolutize(link.attr("href")))
    }

    private fun absolutize(url: String) = if (url.startsWith("http")) url else baseUrl + url

    private companion object {
        // UserAgent complet vérifié contre le site en ligne (le UA court était rejeté)
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
        const val TIMEOUT_MS = 10000
        val PLAY_REGEX = Regex("""play\('([^']+)'.*?loader-(\d+)""")
        val ID_REGEX = Regex("""loader-(\d+)""")
    }
}
