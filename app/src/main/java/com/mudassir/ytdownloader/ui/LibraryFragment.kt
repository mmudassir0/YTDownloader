package com.mudassir.ytdownloader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mudassir.ytdownloader.DownloadItem
import com.mudassir.ytdownloader.R
import com.mudassir.ytdownloader.SavedPlaylist
import com.mudassir.ytdownloader.Settings
import com.mudassir.ytdownloader.Status
import com.mudassir.ytdownloader.Store
import com.mudassir.ytdownloader.Sync
import com.mudassir.ytdownloader.databinding.FragmentLibraryBinding
import com.mudassir.ytdownloader.databinding.ItemPlaylistBinding
import com.mudassir.ytdownloader.databinding.ItemVideoBinding
import com.mudassir.ytdownloader.formatBytes
import com.mudassir.ytdownloader.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Downloaded files (play with the built-in player) and playlists/channels kept for Sync. */
class LibraryFragment : Fragment() {

    private var _b: FragmentLibraryBinding? = null
    private val b get() = _b!!
    private val files = FileAdapter()
    private val lists = ListsAdapter()
    private val syncing = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentLibraryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.tabs.addOnButtonCheckedListener { _, _, _ -> render() }
        b.syncAllButton.setOnClickListener { syncAll() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Store.items.combine(Store.playlists) { _, _ -> }.collect { render() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render() // refresh "continue at" positions after using the player
    }

    private fun render() {
        if (_b == null) return
        val showFiles = b.tabs.checkedButtonId == R.id.filesTab
        b.syncAllButton.isVisible = !showFiles && Store.playlists.value.isNotEmpty()
        if (showFiles) {
            if (b.list.adapter !== files) b.list.adapter = files
            val done = Store.items.value.filter { it.status == Status.DONE && it.fileUri != null }
                .sortedByDescending { it.finishedAt }
            files.submitList(done)
            files.notifyItemRangeChanged(0, done.size)
            b.emptyText.text = "Finished downloads show up here. Tap one to play it."
            b.emptyText.isVisible = done.isEmpty()
        } else {
            if (b.list.adapter !== lists) b.list.adapter = lists
            val pls = Store.playlists.value.sortedBy { it.name.lowercase() }
            lists.submitList(pls)
            lists.notifyItemRangeChanged(0, pls.size)
            b.emptyText.text = "When you download a playlist or channel with “Keep for Sync” ticked, it appears here. " +
                "Sync fetches only the videos you don't have yet."
            b.emptyText.isVisible = pls.isEmpty()
        }
    }

    private fun sync(p: SavedPlaylist) {
        val ctx = requireContext().applicationContext
        syncing += p.url
        render()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { Sync.sync(ctx, p) } }
            syncing -= p.url
            render()
            val msg = result.fold(
                { n -> if (n == 0) "${p.name}: nothing new" else "${p.name}: $n new video(s) queued" },
                { e -> "${p.name}: sync failed (${e.message})" }
            )
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncAll() {
        val ctx = requireContext().applicationContext
        b.syncAllButton.isEnabled = false
        lifecycleScope.launch {
            val (added, errors) = withContext(Dispatchers.IO) { Sync.syncAll(ctx) }
            _b?.syncAllButton?.isEnabled = true
            val msg = (if (added == 0) "Nothing new" else "$added new video(s) queued") +
                if (errors > 0) " · $errors list(s) failed" else ""
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        b.list.adapter = null
        _b = null
    }

    // ---------- adapters ----------

    inner class FileAdapter : ListAdapter<DownloadItem, FileAdapter.Holder>(ITEM_DIFF) {
        inner class Holder(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val b = holder.b
            val ctx = b.root.context
            b.title.text = item.title
            b.thumb.load(item.thumbnail) { crossfade(true) }
            val pos = Settings(ctx).position(item.fileUri!!)
            b.meta.text = listOfNotNull(
                if (item.bytes > 0) formatBytes(item.bytes) else null,
                item.folder,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.finishedAt)),
                if (pos > 5_000) "▶ continue at ${formatDuration(pos / 1000)}" else null
            ).joinToString(" · ")
            b.check.isVisible = false
            b.root.setOnClickListener { Files.play(ctx, item) }
            b.root.setOnLongClickListener { v ->
                PopupMenu(ctx, v).apply {
                    menu.add(0, 1, 0, "Open with…")
                    menu.add(0, 2, 1, "Share")
                    menu.add(0, 3, 2, "Delete file")
                    setOnMenuItemClickListener {
                        when (it.itemId) {
                            1 -> Files.openWith(ctx, item)
                            2 -> Files.share(ctx, item)
                            3 -> MaterialAlertDialogBuilder(ctx)
                                .setTitle("Delete this file?")
                                .setMessage(item.fileName ?: item.title)
                                .setPositiveButton("Delete") { _, _ -> Files.delete(ctx, item) }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                        true
                    }
                }.show()
                true
            }
        }
    }

    inner class ListsAdapter : ListAdapter<SavedPlaylist, ListsAdapter.Holder>(LIST_DIFF) {
        inner class Holder(val b: ItemPlaylistBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = getItem(position)
            val b = holder.b
            val ctx = b.root.context
            val quality = Settings.QUALITIES.firstOrNull { it.second == p.maxHeight }?.first ?: "${p.maxHeight}p"
            b.title.text = p.name
            b.meta.text = listOf(
                if (p.isChannel) "Channel" else "Playlist",
                "${p.seen.size} videos known",
                quality,
                "synced " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.lastSync))
            ).joinToString(" · ")
            val busy = p.url in syncing
            b.syncButton.isEnabled = !busy
            b.syncButton.text = if (busy) "Syncing…" else "Sync"
            b.syncButton.setOnClickListener { sync(p) }
            b.moreButton.setOnClickListener { v ->
                PopupMenu(ctx, v).apply {
                    menu.add(0, 1, 0, "Stop syncing this")
                    setOnMenuItemClickListener {
                        Store.removePlaylist(p.url)
                        true
                    }
                }.show()
            }
        }
    }

    companion object {
        private val ITEM_DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(a: DownloadItem, b: DownloadItem) = a.id == b.id
            override fun areContentsTheSame(a: DownloadItem, b: DownloadItem) = a == b
        }
        private val LIST_DIFF = object : DiffUtil.ItemCallback<SavedPlaylist>() {
            override fun areItemsTheSame(a: SavedPlaylist, b: SavedPlaylist) = a.url == b.url
            override fun areContentsTheSame(a: SavedPlaylist, b: SavedPlaylist) = a == b
        }
    }
}
