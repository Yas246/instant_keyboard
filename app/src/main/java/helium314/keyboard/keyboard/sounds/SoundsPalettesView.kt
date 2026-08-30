// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.SoundItem

class SoundsPalettesView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private var callback: SoundsCallback? = null
    private val adapter = SoundsAdapter()

    init {
        LayoutInflater.from(context).inflate(R.layout.sounds_palettes_view_children, this, true)
        findViewById<RecyclerView>(R.id.sounds_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SoundsPalettesView.adapter
        }
        findViewById<TextView>(R.id.sounds_back_to_keyboard).setOnClickListener {
            callback?.onSwitchToTextKeyboard()
        }
    }

    fun setCallback(cb: SoundsCallback?) { callback = cb }

    fun startSoundsPalettes() { /* search wiring comes in task 7 */ }

    fun stopSoundsPalettes() { /* player release comes in task 7 */ }

    private class SoundsAdapter : RecyclerView.Adapter<SoundsAdapter.Holder>() {
        var items: List<SoundItem> = emptyList()
        var listener: ((SoundItem) -> Unit)? = null
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sound, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.itemView.findViewById<TextView>(R.id.sound_title).text = item.title
            holder.itemView.setOnClickListener { listener?.invoke(item) }
        }
        class Holder(v: android.view.View) : RecyclerView.ViewHolder(v)
    }
}
