package blbl.cat3399.feature.video

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.databinding.ItemVideoDetailHeaderBinding
import blbl.cat3399.feature.player.PlayerPlaylistItem
import java.lang.ref.WeakReference

class VideoDetailHeaderAdapter(
    private val onPlayClick: () -> Unit,
    private val onUpClick: () -> Unit,
    private val onPartClick: (item: PlayerPlaylistItem, index: Int) -> Unit,
    private val onSeasonClick: (item: PlayerPlaylistItem, index: Int) -> Unit,
) : RecyclerView.Adapter<VideoDetailHeaderAdapter.Vh>() {
    private var holderRef: WeakReference<Vh>? = null

    private var title: String? = null
    private var desc: String? = null
    private var coverUrl: String? = null
    private var upName: String? = null
    private var upAvatar: String? = null
    private var seasonTitle: String? = null
    private var parts: List<PlayerPlaylistItem> = emptyList()
    private var seasonItems: List<PlayerPlaylistItem> = emptyList()
    private var seasonIndex: Int? = null

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = 1L

    override fun getItemCount(): Int = 1

    fun requestFocusPlay(): Boolean = holderRef?.get()?.binding?.btnPlay?.requestFocus() == true

    fun invalidateSizing() {
        notifyItemChanged(0)
    }

    fun update(
        title: String?,
        desc: String?,
        coverUrl: String?,
        upName: String?,
        upAvatar: String?,
        seasonTitle: String?,
        parts: List<PlayerPlaylistItem>,
        seasonItems: List<PlayerPlaylistItem>,
        seasonIndex: Int?,
    ) {
        this.title = title
        this.desc = desc
        this.coverUrl = coverUrl
        this.upName = upName
        this.upAvatar = upAvatar
        this.seasonTitle = seasonTitle
        this.parts = parts
        this.seasonItems = seasonItems
        this.seasonIndex = seasonIndex
        notifyItemChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemVideoDetailHeaderBinding.inflate(
                LayoutInflater.from(parent.context).cloneInUserScale(parent.context),
                parent,
                false,
            )
        return Vh(binding, onPlayClick, onUpClick, onPartClick, onSeasonClick)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(
            title = title,
            desc = desc,
            coverUrl = coverUrl,
            upName = upName,
            upAvatar = upAvatar,
            seasonTitle = seasonTitle,
            parts = parts,
            seasonItems = seasonItems,
            seasonIndex = seasonIndex,
        )
    }

    override fun onViewAttachedToWindow(holder: Vh) {
        super.onViewAttachedToWindow(holder)
        holderRef = WeakReference(holder)
    }

    override fun onViewDetachedFromWindow(holder: Vh) {
        val current = holderRef?.get()
        if (current === holder) holderRef = null
        super.onViewDetachedFromWindow(holder)
    }

    class Vh(
        val binding: ItemVideoDetailHeaderBinding,
        private val onPlayClick: () -> Unit,
        private val onUpClick: () -> Unit,
        private val onPartClick: (item: PlayerPlaylistItem, index: Int) -> Unit,
        private val onSeasonClick: (item: PlayerPlaylistItem, index: Int) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        private val partsAdapter = VideoDetailPlaylistAdapter { item, index -> onPartClick(item, index) }
        private val seasonAdapter = VideoDetailPlaylistAdapter { item, index -> onSeasonClick(item, index) }
        private var lastSeasonAutoScrollKey: String? = null

        init {
            binding.btnPlay.setOnClickListener { onPlayClick() }
            binding.cardUp.setOnClickListener { onUpClick() }

            binding.recyclerParts.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.recyclerParts.adapter = partsAdapter

            binding.recyclerSeason.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.recyclerSeason.adapter = seasonAdapter
        }

        fun bind(
            title: String?,
            desc: String?,
            coverUrl: String?,
            upName: String?,
            upAvatar: String?,
            seasonTitle: String?,
            parts: List<PlayerPlaylistItem>,
            seasonItems: List<PlayerPlaylistItem>,
            seasonIndex: Int?,
        ) {
            binding.tvTitle.text = title?.trim().takeIf { !it.isNullOrBlank() } ?: "-"

            val safeCover = coverUrl?.trim().takeIf { !it.isNullOrBlank() }
            if (safeCover != null) {
                ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(safeCover))
            }

            val safeUpName = upName?.trim().takeIf { !it.isNullOrBlank() }
            binding.cardUp.isVisible = safeUpName != null
            if (safeUpName != null) {
                binding.tvUpName.text = safeUpName
                ImageLoader.loadInto(binding.ivUpAvatar, ImageUrl.avatar(upAvatar))
            }

            val safeDesc = desc?.trim().takeIf { !it.isNullOrBlank() }
            binding.tvDesc.text = safeDesc ?: "暂无简介"

            val showParts = parts.size > 1
            binding.tvPartsHeader.isVisible = showParts
            binding.recyclerParts.isVisible = showParts
            if (showParts) {
                binding.tvPartsHeader.text = "分P（${parts.size}）"
                partsAdapter.submit(parts)
            } else {
                partsAdapter.submit(emptyList())
            }

            val showSeason = seasonItems.size > 1
            binding.tvSeasonHeader.isVisible = showSeason
            binding.recyclerSeason.isVisible = showSeason
            if (showSeason) {
                val safeTitle = seasonTitle?.trim().takeIf { !it.isNullOrBlank() }
                binding.tvSeasonHeader.text = safeTitle?.let { "合集：$it" } ?: "合集（${seasonItems.size}）"
                seasonAdapter.submit(seasonItems)
                maybeAutoScrollSeason(seasonItems, seasonIndex)
            } else {
                seasonAdapter.submit(emptyList())
                lastSeasonAutoScrollKey = null
            }
        }

        private fun maybeAutoScrollSeason(seasonItems: List<PlayerPlaylistItem>, seasonIndex: Int?) {
            val idx = seasonIndex?.takeIf { it in seasonItems.indices } ?: return
            val targetBvid = seasonItems[idx].bvid.trim()
            if (targetBvid.isBlank()) return

            val firstBvid = seasonItems.firstOrNull()?.bvid?.trim().orEmpty()
            val lastBvid = seasonItems.lastOrNull()?.bvid?.trim().orEmpty()
            val autoScrollKey = "$targetBvid|$idx|${seasonItems.size}|$firstBvid|$lastBvid"
            if (autoScrollKey == lastSeasonAutoScrollKey) return
            lastSeasonAutoScrollKey = autoScrollKey

            binding.recyclerSeason.post {
                val lm = binding.recyclerSeason.layoutManager as? LinearLayoutManager ?: return@post
                lm.scrollToPositionWithOffset(idx, binding.recyclerSeason.paddingLeft)
            }
        }
    }
}
