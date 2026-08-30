// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.MyInstantsSource
import helium314.keyboard.soundscore.SoundItem

class SoundsPalettesView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val source = MyInstantsSource()
    private var callback: SoundsCallback? = null
    private val adapter = SoundsAdapter()
    private var player: MediaPlayer? = null
    private var searchRunnable: Runnable? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.sounds_palettes_view_children, this, true)
        findViewById<RecyclerView>(R.id.sounds_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SoundsPalettesView.adapter
        }
        findViewById<TextView>(R.id.sounds_back_to_keyboard).setOnClickListener {
            callback?.onSwitchToTextKeyboard()
        }
        adapter.onPlay = { item -> playPreview(item) }
        val edit = findViewById<EditText>(R.id.sounds_search_edit)
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(edit.text.toString()); true } else false
        }
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let(mainHandler::removeCallbacks)
                val q = s?.toString()?.trim() ?: ""
                if (q.length < 2) return
                searchRunnable = Runnable { runSearch(q) }.also { mainHandler.postDelayed(it, 400) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        // onglets : populaires affiche trending, favoris/récents remplis en task 9
        findViewById<TextView>(R.id.sounds_tab_trending).setOnClickListener {
            loadInBackground { source.trending() }
        }
    }

    fun setCallback(cb: SoundsCallback?) { callback = cb }

    fun startSoundsPalettes() { loadInBackground { source.trending() } }

    fun stopSoundsPalettes() {
        stopPreview()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun runSearch(query: String) = loadInBackground { source.search(query) }

    private fun loadInBackground(load: () -> List<SoundItem>) {
        showStatus(context.getString(R.string.sounds_empty)) // sera remplacé par un spinner plus tard
        Thread {
            val items = try { load() } catch (e: Exception) {
                mainHandler.post { showStatus(context.getString(R.string.sounds_error_network)) }
                return@Thread
            }
            mainHandler.post {
                findViewById<RecyclerView>(R.id.sounds_recycler).visibility = if (items.isEmpty()) GONE else VISIBLE
                findViewById<TextView>(R.id.sounds_status_view).visibility = if (items.isEmpty()) VISIBLE else GONE
                adapter.items = items
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun showStatus(text: String) {
        findViewById<RecyclerView>(R.id.sounds_recycler).visibility = GONE
        findViewById<TextView>(R.id.sounds_status_view).apply { visibility = VISIBLE; this.text = text }
    }

    private fun playPreview(item: SoundItem) {
        stopPreview()
        Thread {
            try {
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                p.setDataSource(item.mediaUrl)
                p.setOnCompletionListener { mainHandler.post { stopPreview() } }
                p.prepare() // blocking, background thread
                player = p
                p.start()
            } catch (e: Exception) {
                player = null
                mainHandler.post {
                    android.widget.Toast.makeText(context, R.string.sounds_preview_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun stopPreview() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    private class SoundsAdapter : RecyclerView.Adapter<SoundsAdapter.Holder>() {
        var items: List<SoundItem> = emptyList()
        var onPlay: ((SoundItem) -> Unit)? = null   // ▶ branché en task 7
        var onSend: ((SoundItem) -> Unit)? = null   // ➤ branché en task 8
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sound, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val view = holder.itemView
            view.findViewById<TextView>(R.id.sound_title).text = item.title
            view.findViewById<TextView>(R.id.sound_play).setOnClickListener { onPlay?.invoke(item) }
            view.findViewById<TextView>(R.id.sound_send).setOnClickListener { onSend?.invoke(item) }
        }
        class Holder(v: android.view.View) : RecyclerView.ViewHolder(v)
    }
}