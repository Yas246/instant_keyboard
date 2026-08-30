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
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.MyInstantsSource
import helium314.keyboard.soundscore.SoundItem
import helium314.keyboard.soundscore.SoundStore

class SoundsPalettesView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val source = MyInstantsSource()
    private var callback: SoundsCallback? = null
    private val adapter = SoundsAdapter()
    private var player: MediaPlayer? = null
    private var searchRunnable: Runnable? = null
    private var previewGeneration = 0
    private var searchGeneration = 0
    private val store by lazy {
        SoundStore(java.io.File(context.filesDir, "sounds_store.properties"))
    }
    private var currentTab = TAB_TRENDING
    private var pendingQuery: String? = null

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
        adapter.onSend = { item ->
            store.addRecent(item) // récents mis à jour avant l'envoi effectif
            callback?.onSendSound(item)
        }
        adapter.isFavorite = { store.isFavorite(it.id) }
        adapter.favoriteListener = { item ->
            store.toggleFavorite(item)
            adapter.notifyDataSetChanged()
        }
        val edit = findViewById<EditText>(R.id.sounds_search_edit)
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchRunnable?.let(mainHandler::removeCallbacks)
                runSearch(edit.text.toString()); true
            } else false
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
        // onglets : populaires (réseau), favoris / récents (store local)
        findViewById<TextView>(R.id.sounds_tab_trending).setOnClickListener { switchTab(TAB_TRENDING) }
        findViewById<TextView>(R.id.sounds_tab_favorites).setOnClickListener { switchTab(TAB_FAVORITES) }
        findViewById<TextView>(R.id.sounds_tab_recents).setOnClickListener { switchTab(TAB_RECENTS) }
        // taper le message d'état relance la recherche ou l'onglet courant : « réessayer »
        findViewById<TextView>(R.id.sounds_status_view).setOnClickListener {
            val q = pendingQuery
            if (q != null) runSearch(q) else switchTab(currentTab)
        }
    }

    fun setCallback(cb: SoundsCallback?) { callback = cb }

    fun startSoundsPalettes() {
        currentTab = TAB_TRENDING // l'onglet affiché suit le contenu chargé
        loadInBackground { source.trending() }
    }

    fun stopSoundsPalettes() {
        searchGeneration++
        stopPreview()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun runSearch(query: String) {
        pendingQuery = query
        loadInBackground { source.search(query) }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        when (tab) {
            TAB_TRENDING -> loadInBackground { source.trending() }
            TAB_FAVORITES -> { searchGeneration++; showList(store.favorites()) }
            TAB_RECENTS -> { searchGeneration++; showList(store.recents()) }
        }
    }

    private fun showList(items: List<SoundItem>) {
        if (items.isEmpty()) { showStatus(context.getString(R.string.sounds_empty)); return }
        findViewById<RecyclerView>(R.id.sounds_recycler).visibility = VISIBLE
        findViewById<TextView>(R.id.sounds_status_view).visibility = GONE
        adapter.items = items
        adapter.notifyDataSetChanged()
    }

    private fun loadInBackground(load: () -> List<SoundItem>) {
        val gen = ++searchGeneration
        showStatus(context.getString(R.string.sounds_empty)) // sera remplacé par un spinner plus tard
        Thread {
            val items = try { load() } catch (e: Exception) {
                if (gen == searchGeneration) {
                    mainHandler.post { showStatus(context.getString(R.string.sounds_error_network)) }
                }
                return@Thread
            }
            if (gen != searchGeneration) return@Thread
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
        val gen = previewGeneration
        Thread {
            val p = MediaPlayer()
            try {
                p.setAudioAttributes(
                    AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                p.setDataSource(item.mediaUrl)
                p.setOnCompletionListener { mainHandler.post { stopPreview() } }
                p.prepare() // blocking, background thread
                if (gen != previewGeneration) {
                    runCatching { p.release() }
                    return@Thread
                }
                player = p
                p.start()
            } catch (e: Exception) {
                runCatching { p.release() }
                if (gen == previewGeneration) {
                    player = null
                    mainHandler.post {
                        android.widget.Toast.makeText(context, R.string.sounds_preview_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }

    private fun stopPreview() {
        previewGeneration++
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    private class SoundsAdapter : RecyclerView.Adapter<SoundsAdapter.Holder>() {
        var items: List<SoundItem> = emptyList()
        var onPlay: ((SoundItem) -> Unit)? = null   // ▶ branché en task 7
        var onSend: ((SoundItem) -> Unit)? = null   // ➤ branché en task 8
        var favoriteListener: ((SoundItem) -> Unit)? = null
        var isFavorite: (SoundItem) -> Boolean = { false }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sound, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val view = holder.itemView
            view.findViewById<TextView>(R.id.sound_title).text = item.title
            view.findViewById<TextView>(R.id.sound_play).setOnClickListener { onPlay?.invoke(item) }
            view.findViewById<TextView>(R.id.sound_send).setOnClickListener { onSend?.invoke(item) }
            val fav = view.findViewById<TextView>(R.id.sound_favorite)
            fav.text = if (isFavorite(item)) "★" else "☆"
            fav.setOnClickListener { favoriteListener?.invoke(item) }
        }
        class Holder(v: android.view.View) : RecyclerView.ViewHolder(v)
    }

    private companion object { const val TAB_TRENDING = 0; const val TAB_FAVORITES = 1; const val TAB_RECENTS = 2 }
}