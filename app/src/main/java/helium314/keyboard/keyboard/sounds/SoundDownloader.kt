// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.SoundItem
import org.jsoup.Jsoup
import java.io.File

object SoundDownloader {
    private const val MAX_SOUND_BYTES = 15 * 1024 * 1024 // plafond 15 Mo : un ByteArray non borné peut tuer l'IME (OOM)

    /**
     * Expérimentation : tente un commitContent direct (audio/mpeg) si l'éditeur déclare accepter
     * de l'audio, sinon retombe sur la feuille de partage ACTION_SEND (comportement historique).
     */
    @JvmStatic
    fun send(context: Context, item: SoundItem) {
        // toast routé par KeyboardSwitcher : fallback in-IME sur API 33+ sans POST_NOTIFICATIONS
        main { KeyboardSwitcher.getInstance().showToast(context.getString(R.string.sounds_sending), true) }
        download(context, item,
            onDone = { file ->
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.sounds.fileprovider", file)
                // le commitContent doit tourner sur le main thread ; on y route aussi le repli
                main {
                    try {
                        val ime = context as? LatinIME
                        val ei = ime?.currentInputEditorInfo
                        val ic = ime?.currentInputConnection
                        val mimes = ei?.let { EditorInfoCompat.getContentMimeTypes(it) }
                        val audioOk = mimes?.any { ClipDescription.compareMimeTypes(it, "audio/*") } == true
                        val sent = if (ic != null && audioOk) {
                            InputConnectionCompat.commitContent(
                                ic, ei!!,
                                InputContentInfoCompat(
                                    uri,
                                    ClipDescription(item.title, arrayOf("audio/mpeg")),
                                    null),
                                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                                null)
                        } else false
                        if (sent) {
                            KeyboardSwitcher.getInstance().showToast(context.getString(R.string.sounds_direct_sent), true)
                        } else {
                            share(context, item, uri)
                            KeyboardSwitcher.getInstance().showToast(context.getString(R.string.sounds_share_fallback), true)
                        }
                    } catch (t: Throwable) {
                        // aucun scénario ne doit tuer le clavier : le partage reste la voie sûre
                        try {
                            share(context, item, uri)
                            KeyboardSwitcher.getInstance().showToast(context.getString(R.string.sounds_share_fallback), true)
                        } catch (t2: Throwable) {
                            KeyboardSwitcher.getInstance().showToast(context.getString(R.string.sounds_send_failed), false)
                        }
                    }
                }
            },
            onError = {
                main { KeyboardSwitcher.getInstance().showToast(context.getString(R.string.sounds_send_failed), false) }
            })
    }

    private fun download(context: Context, item: SoundItem, onDone: (File) -> Unit, onError: () -> Unit) {
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
                onDone(file)
            } catch (t: Throwable) {
                onError()
            }
        }.start()
    }

    // feuille de partage d'origine, inchangée
    private fun share(context: Context, item: SoundItem, uri: android.net.Uri) {
        val share = Intent(Intent.ACTION_SEND)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(share, item.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun sanitize(title: String) =
        title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(60).ifBlank { "son" }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}
