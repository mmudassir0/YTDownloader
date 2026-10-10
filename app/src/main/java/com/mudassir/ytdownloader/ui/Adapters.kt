package com.mudassir.ytdownloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.mudassir.ytdownloader.VideoEntry
import com.mudassir.ytdownloader.databinding.ItemVideoBinding
import com.mudassir.ytdownloader.formatDuration

/** Wraps a single, already-inflated view (a header or footer) so it can sit in a ConcatAdapter. */
class SingleViewAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var shown = true
        set(v) {
            if (field == v) return
            field = v
            if (v) notifyItemInserted(0) else notifyItemRemoved(0)
        }

    override fun getItemCount() = if (shown) 1 else 0
    override fun getItemViewType(position: Int) = view.id.takeIf { it != View.NO_ID } ?: System.identityHashCode(view)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
        view.also { (it.parent as? ViewGroup)?.removeView(it) }
    ) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
}

/** Videos from a playlist, channel or search. Checkboxes show when [selectable]. */
class VideoEntryAdapter(
    private val onClick: (VideoEntry) -> Unit
) : ListAdapter<VideoEntry, VideoEntryAdapter.Holder>(DIFF) {

    var selectable = false
        set(v) {
            if (field != v) { field = v; notifyItemRangeChanged(0, itemCount) }
        }
    var selected: Set<String> = emptySet()
        set(v) {
            if (field != v) { field = v; notifyItemRangeChanged(0, itemCount, PAYLOAD_CHECK) }
        }

    class Holder(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CHECK)) {
            holder.b.check.isChecked = getItem(position).id in selected
        } else super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val v = getItem(position)
        val b = holder.b
        b.title.text = v.title
        b.meta.text = listOf(v.uploader, formatDuration(v.durationSec)).filter { it.isNotBlank() }.joinToString(" · ")
        b.thumb.load(v.thumbnail) { crossfade(true) }
        b.check.isVisible = selectable
        b.check.isChecked = v.id in selected
        b.root.setOnClickListener { onClick(v) }
    }

    companion object {
        private const val PAYLOAD_CHECK = "check"
        private val DIFF = object : DiffUtil.ItemCallback<VideoEntry>() {
            override fun areItemsTheSame(a: VideoEntry, b: VideoEntry) = a.id == b.id
            override fun areContentsTheSame(a: VideoEntry, b: VideoEntry) = a == b
        }
    }
}
