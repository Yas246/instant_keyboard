// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.SoundItem
import org.jsoup.Jsoup
import java.io.File

object SoundDownloader {
    private const val MAX_SOUND_BYTES = 15 * 1024 * 1024 // plafond 15 Mo : un ByteArray non borné peut tuer l'IME (OOM)

    @JvmStatic
    fun downloadAndShare(context: Context, item: SoundItem) {
        // toast routé par KeyboardSwitcher : fallback in-IME sur API 33+ sans POST_NOTIFICATIONS
        main { KeyboardSwitcher.getInstance().showToast(context.getString(R.string.sounds_sending), true) }
        Thread {
            try {
                val bytes = Jsoup.connect(item.mediaUrl)
                    .ignoreContentType(true)
                    .maxBodySize(MAX_SOUND_BYTES)
                    .execute()
                    .bodyAsBytes()
                val dir = File(context.cacheDir, "sounds").apply { mkdirs() }
                val file = File(dir, sanitize(item.title) + ".mp3")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.sounds.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND)
                    .setType("audio/*")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(
                    Intent.createChooser(share, item.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                main {
                    KeyboardSwitcher.getInstance().showToast(context.getString(R.string.sounds_send_failed), false)
                }
            }
        }.start()
    }

    private fun sanitize(title: String) =
        title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(60).ifBlank { "son" }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}
