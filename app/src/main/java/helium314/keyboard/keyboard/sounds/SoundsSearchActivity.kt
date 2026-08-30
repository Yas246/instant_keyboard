// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.MyInstantsSource
import helium314.keyboard.soundscore.SoundItem
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recherche plein écran myinstants. Un EditText dans le panneau de l'IME ne peut pas
 * recevoir le texte (notre propre clavier est la méthode de saisie active) ; HeliBoard
 * résout ce problème pour les emojis avec une activity transparente (EmojiSearchActivity),
 * on mirror ce mécanisme ici. Le clavier reste visible grâce à
 * android:windowSoftInputMode="stateAlwaysVisible|adjustResize" dans le manifest.
 */
class SoundsSearchActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val source = MyInstantsSource()
    private val searchGeneration = AtomicInteger(0)
    private val previewGeneration = AtomicInteger(0)
    private val adapter = ResultsAdapter()
    private val store by lazy { SoundStores.get(this) }

    @Volatile private var player: MediaPlayer? = null
    private var searchRunnable: Runnable? = null
    private var pendingQuery: String? = null
    private lateinit var statusView: TextView
    private lateinit var recycler: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // notre IME affiche sinon le panneau sons (aucune touche pour taper) :
        // bascule en clavier texte, comme EmojiSearchActivity.init() le fait en amont
        KeyboardSwitcher.getInstance().setAlphabetKeyboard()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(android.R.attr.colorBackground))
        }
        val edit = EditText(this).apply {
            hint = getString(R.string.search_sounds_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            maxLines = 1
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        statusView = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(24), dp(12), dp(24))
            visibility = android.view.View.GONE
        }
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SoundsSearchActivity)
            adapter = this@SoundsSearchActivity.adapter
        }
        root.addView(edit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        adapter.onPlay = { playPreview(it) }
        adapter.onSend = { item ->
            store.addRecent(item) // mêmes récents que le panneau
            SoundDownloader.downloadAndShare(this, item) // contexte activity : pas de NEW_TASK requis
            finish()
        }
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchRunnable?.let(mainHandler::removeCallbacks)
                runSearch(edit.text.toString()); true
            } else false
        }
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let(mainHandler::removeCallbacks)
                val q = s?.toString()?.trim() ?: ""
                if (q.length < 2) return
                searchRunnable = Runnable { runSearch(q) }.also { mainHandler.postDelayed(it, 400) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        // taper le message d'état relance la recherche : « réessayer »
        statusView.setOnClickListener { pendingQuery?.let { runSearch(it) } }
    }

    override fun onStop() {
        // restaure le panneau sons à la fermeture (partagé, retour, ou chooser par-dessus) :
        // miroir du EmojiSearchActivity -> setEmojiKeyboard d'HeliBoard, en appel direct
        // (même processus, pas de détour par un intent de service ni de délai nécessaire)
        KeyboardSwitcher.getInstance().setSoundsKeyboard()
        super.onStop()
    }

    override fun onDestroy() {
        searchGeneration.incrementAndGet()
        stopPreview()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun runSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        pendingQuery = q
        val gen = searchGeneration.incrementAndGet()
        showStatus(getString(R.string.sounds_empty))
        Thread {
            val items = try { source.search(q) } catch (e: Exception) {
                if (searchGeneration.get() == gen) {
                    val message = getString(R.string.sounds_error_network) +
                        "\n(" + (e.message ?: e.javaClass.simpleName) + ")" +
                        "\n" + getString(R.string.sounds_retry)
                    runOnUiThread { showStatus(message) }
                }
                return@Thread
            }
            if (searchGeneration.get() != gen) return@Thread
            runOnUiThread {
                if (items.isEmpty()) {
                    // aucun résultat : message + liste masquée (miroir de showList du panneau)
                    showStatus(getString(R.string.sounds_no_results))
                } else {
                    statusView.visibility = android.view.View.GONE
                    recycler.visibility = android.view.View.VISIBLE
                    adapter.items = items
                    adapter.notifyDataSetChanged()
                }
            }
        }.start()
    }

    private fun showStatus(text: String) {
        recycler.visibility = android.view.View.GONE
        statusView.apply { visibility = android.view.View.VISIBLE; this.text = text }
    }

    private fun playPreview(item: SoundItem) {
        stopPreview()
        val gen = previewGeneration.get()
        Thread {
            val p = MediaPlayer()
            try {
                p.setAudioAttributes(
                    AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                p.setDataSource(item.mediaUrl)
                p.setOnCompletionListener { mainHandler.post { stopPreview() } }
                p.prepare() // bloquant, thread en arrière-plan
                if (previewGeneration.get() != gen) {
                    runCatching { p.release() }
                    return@Thread
                }
                player = p
                p.start()
            } catch (t: Throwable) {
                runCatching { p.release() }
                if (previewGeneration.get() == gen) {
                    player = null
                    runOnUiThread {
                        KeyboardSwitcher.getInstance().showToast(getString(R.string.sounds_preview_failed), true)
                    }
                }
            }
        }.start()
    }

    private fun stopPreview() {
        previewGeneration.incrementAndGet()
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    private fun themeColor(attr: Int): Int {
        val typedValue = TypedValue()
        return if (theme.resolveAttribute(attr, typedValue, true)) typedValue.data else Color.DKGRAY
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.Holder>() {
        var items: List<SoundItem> = emptyList()
        var onPlay: ((SoundItem) -> Unit)? = null
        var onSend: ((SoundItem) -> Unit)? = null
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sound, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val view = holder.itemView
            view.findViewById<TextView>(R.id.sound_title).text = item.title
            view.findViewById<TextView>(R.id.sound_play).setOnClickListener { onPlay?.invoke(item) }
            view.findViewById<TextView>(R.id.sound_send).setOnClickListener { onSend?.invoke(item) }
            view.findViewById<TextView>(R.id.sound_favorite).visibility = android.view.View.GONE
        }
        class Holder(v: android.view.View) : RecyclerView.ViewHolder(v)
    }
}
