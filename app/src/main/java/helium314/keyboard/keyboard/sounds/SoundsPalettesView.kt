// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.MyInstantsSource
import helium314.keyboard.soundscore.SoundItem

/**
 * Panneau sons « dans le clavier » (style recherche GIF SwiftKey/Gboard) : le clavier de frappe
 * reste visible SOUS le panneau et les touches y sont routées par KeyboardActionListenerImpl.
 * La fenêtre de l'IME n'ayant pas le focus système, la barre de requête est une simple TextView
 * non focusable — jamais d'EditText, la requête n'est jamais commitée dans le champ de l'app,
 * et la conversation reste visible au-dessus (pas d'activity plein écran).
 */
class SoundsPalettesView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val source = MyInstantsSource()
    private var callback: SoundsCallback? = null
    private val adapter = SoundsAdapter()
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var previewGeneration = 0
    @Volatile private var searchGeneration = 0
    private val store by lazy { SoundStores.get(context) }
    private var currentTab = TAB_TRENDING
    // requête de recherche : alimentée par appendSearchChar/backspaceSearch (pipeline de touches)
    private var query = ""
    private var searchRunnable: Runnable? = null
    // curseur simulé : la fenêtre IME n'a pas le focus système, on dessine un caret qui clignote
    private var cursorVisible = true
    private val cursorRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            updateQueryView()
            mainHandler.postDelayed(this, 500)
        }
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.sounds_palettes_view_children, this, true)
        findViewById<RecyclerView>(R.id.sounds_recycler).apply {
            layoutManager = GridLayoutManager(context, GRID_SPAN_COUNT)
            adapter = this@SoundsPalettesView.adapter
        }
        findViewById<TextView>(R.id.sounds_back_to_keyboard).setOnClickListener {
            callback?.onSwitchToTextKeyboard()
        }
        findViewById<TextView>(R.id.sounds_clear_search).setOnClickListener { clearSearch() }
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
        // onglets : populaires (réseau), favoris / récents (store local) — quitter la recherche
        findViewById<TextView>(R.id.sounds_tab_trending).setOnClickListener { switchTab(TAB_TRENDING) }
        findViewById<TextView>(R.id.sounds_tab_favorites).setOnClickListener { switchTab(TAB_FAVORITES) }
        findViewById<TextView>(R.id.sounds_tab_recents).setOnClickListener { switchTab(TAB_RECENTS) }
        // taper le message d'état relance l'onglet courant ou la recherche : « réessayer »
        findViewById<TextView>(R.id.sounds_status_view).setOnClickListener {
            if (query.isEmpty()) switchTab(currentTab) else scheduleSearch(immediate = true)
        }
    }

    fun setCallback(cb: SoundsCallback?) { callback = cb }

    fun startSoundsPalettes() {
        // l'état (onglet, requête, résultats) survit à la fermeture/réouverture du panneau :
        // on resynchronise la barre, et si une requête est conservée on relance sa recherche
        // (une recherche en vol / en debounce a été annulée par stopSoundsPalettes)
        mainHandler.removeCallbacks(cursorRunnable)
        cursorVisible = true
        mainHandler.postDelayed(cursorRunnable, 500)
        updateQueryView()
        if (query.isNotEmpty()) { scheduleSearch(immediate = true); return }
        if (adapter.items.isEmpty()) loadInBackground { source.trending() }
    }

    fun stopSoundsPalettes() {
        searchGeneration++
        cancelPendingSearch()
        stopPreview()
        mainHandler.removeCallbacks(cursorRunnable)
        mainHandler.removeCallbacksAndMessages(null)
    }

    // -------- recherche « dans le clavier » : API appelée par KeyboardActionListenerImpl --------

    /** ajoute un caractère à la requête et (re)lance la recherche avec debounce */
    fun appendSearchChar(c: Char) {
        query += c
        updateQueryView()
        scheduleSearch()
    }

    /** efface le dernier caractère ; requête vide -> retour au contenu de l'onglet courant */
    fun backspaceSearch() {
        if (query.isEmpty()) return
        query = query.dropLast(1)
        updateQueryView()
        if (query.isEmpty()) switchTab(currentTab) else scheduleSearch()
    }

    /** vide la requête et revient au contenu de l'onglet courant */
    fun clearSearch() = switchTab(currentTab)

    fun queryText(): String = query

    private fun scheduleSearch(immediate: Boolean = false) {
        cancelPendingSearch()
        val runnable = Runnable { runSearch() }
        searchRunnable = runnable
        if (immediate) mainHandler.post(runnable)
        else mainHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
    }

    private fun cancelPendingSearch() {
        searchRunnable?.let(mainHandler::removeCallbacks)
        searchRunnable = null
    }

    private fun runSearch() {
        searchRunnable = null
        val q = query.trim()
        if (q.isEmpty()) { switchTab(currentTab); return }
        loadInBackground(context.getString(R.string.sounds_no_results)) { source.search(q) }
    }

    private fun updateQueryView() {
        // le caret n'existe que dans l'affichage : la requête réelle reste intacte
        val base = if (query.isEmpty()) context.getString(R.string.search_sounds_hint) else query
        findViewById<TextView>(R.id.sounds_query_view).text = base + if (cursorVisible) CURSOR_ON else CURSOR_OFF
        findViewById<TextView>(R.id.sounds_clear_search).visibility =
            if (query.isEmpty()) INVISIBLE else VISIBLE
    }

    // ---------------------------------------------------------------------------------------------

    private fun switchTab(tab: Int) {
        currentTab = tab
        // changer d'onglet quitte la recherche : la barre revient au texte d'aide
        cancelPendingSearch()
        query = ""
        updateQueryView()
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

    // le lambda en DERNIER paramètre : appelable en trailing lambda, emptyMessage gardant sa valeur par défaut
    private fun loadInBackground(emptyMessage: String = context.getString(R.string.sounds_empty), load: () -> List<SoundItem>) {
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
                if (items.isEmpty()) showStatus(emptyMessage) else showList(items)
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
        var onPlay: ((SoundItem) -> Unit)? = null
        var onSend: ((SoundItem) -> Unit)? = null
        var favoriteListener: ((SoundItem) -> Unit)? = null
        var isFavorite: (SoundItem) -> Boolean = { false }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sound, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val view = holder.itemView
            view.findViewById<TextView>(R.id.sound_title).text = item.title
            // toute la tuile est une cible de lecture : le geste naturel déclenche onPlay
            view.tap { onPlay?.invoke(item) }
            view.findViewById<TextView>(R.id.sound_play).tap { onPlay?.invoke(item) }
            view.findViewById<TextView>(R.id.sound_send).tap { onSend?.invoke(item) }
            val fav = view.findViewById<TextView>(R.id.sound_favorite)
            fav.text = if (isFavorite(item)) "★" else "☆"
            fav.tap { favoriteListener?.invoke(item) }
        }
        // retour tactile systématique : « quand je clique sur play il faut que je SENS que j'ai cliqué »
        private fun View.tap(action: () -> Unit) {
            setOnClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                action()
            }
        }
        class Holder(v: View) : RecyclerView.ViewHolder(v)
    }

    private companion object {
        const val TAB_TRENDING = 0; const val TAB_FAVORITES = 1; const val TAB_RECENTS = 2
        const val GRID_SPAN_COUNT = 3
        const val SEARCH_DEBOUNCE_MS = 350L
        // caret décoratif : uniquement dans l'affichage, jamais dans `query`
        const val CURSOR_ON = "▍"
        const val CURSOR_OFF = " "
    }
}
