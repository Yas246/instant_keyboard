// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.MyInstantsSource
import helium314.keyboard.soundscore.SoundItem
import helium314.keyboard.soundscore.SoundStore

class SoundsPalettesView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val source = MyInstantsSource()
    private var callback: SoundsCallback? = null
    private val adapter = SoundsAdapter()
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var previewGeneration = 0
    @Volatile private var searchGeneration = 0
    private val store by lazy {
        SoundStore(java.io.File(context.filesDir, "sounds_store.properties"))
    }
    private var currentTab = TAB_TRENDING

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
        // Plan B : la saisie passe par SoundsSearchActivity. Un EditText dans le panneau de
        // l'IME ne reçoit jamais le texte (notre propre clavier est la méthode de saisie
        // active) ; HeliBoard utilise le même mécanisme d'activity pour la recherche emoji.
        findViewById<TextView>(R.id.sounds_search_button).apply {
            text = "🔍 " + context.getString(R.string.sounds_search_action)
            setOnClickListener {
                context.startActivity(
                    Intent(context, SoundsSearchActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                )
            }
        }
        // onglets : populaires (réseau), favoris / récents (store local)
        findViewById<TextView>(R.id.sounds_tab_trending).setOnClickListener { switchTab(TAB_TRENDING) }
        findViewById<TextView>(R.id.sounds_tab_favorites).setOnClickListener { switchTab(TAB_FAVORITES) }
        findViewById<TextView>(R.id.sounds_tab_recents).setOnClickListener { switchTab(TAB_RECENTS) }
        // taper le message d'état relance l'onglet courant : « réessayer »
        // (la recherche vit dans SoundsSearchActivity, plus d'état de requête ici)
        findViewById<TextView>(R.id.sounds_status_view).setOnClickListener { switchTab(currentTab) }
    }

    fun setCallback(cb: SoundsCallback?) { callback = cb }

    fun startSoundsPalettes() {
        // l'état (onglet, recherche) survit à la fermeture/réouverture du panneau
        if (adapter.items.isEmpty()) loadInBackground { source.trending() }
    }

    fun stopSoundsPalettes() {
        searchGeneration++
        stopPreview()
        mainHandler.removeCallbacksAndMessages(null)
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
                    // message diagnostic + indice « réessayer » : le statut est cliquable pour relancer
                    val message = context.getString(R.string.sounds_error_network) +
                        "\n(" + (e.message ?: e.javaClass.simpleName) + ")" +
                        "\n" + context.getString(R.string.sounds_retry)
                    mainHandler.post { showStatus(message) }
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
            } catch (t: Throwable) {
                runCatching { p.release() }
                if (gen == previewGeneration) {
                    player = null
                    mainHandler.post {
                        // toast routé par KeyboardSwitcher : fallback in-IME sur API 33+ sans POST_NOTIFICATIONS
                        KeyboardSwitcher.getInstance().showToast(context.getString(R.string.sounds_preview_failed), true)
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