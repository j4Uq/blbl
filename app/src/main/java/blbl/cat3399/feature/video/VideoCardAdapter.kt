package blbl.cat3399.feature.video

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemVideoCardBinding

class VideoCardAdapter(
    private val onClick: (VideoCard, Int) -> Unit,
    private val onLongClick: ((VideoCard, Int) -> Boolean)? = null,
    private val fixedItemWidthDimenRes: Int? = null,
    private val fixedItemMarginDimenRes: Int? = null,
    private val stableIdKey: ((VideoCard) -> String)? = null,
) : RecyclerView.Adapter<VideoCardAdapter.Vh>() {
    private val items = ArrayList<VideoCard>()

    init {
        setHasStableIds(true)
    }

    fun invalidateSizing() {
        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<VideoCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<VideoCard>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun snapshot(): List<VideoCard> = items.toList()

    override fun getItemId(position: Int): Long {
        val item = items[position]
        val key = stableIdKey?.invoke(item) ?: item.stableKey()
        return key.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding =
            ItemVideoCardBinding.inflate(
                LayoutInflater.from(parent.context).cloneInUserScale(parent.context),
                parent,
                false,
            )
        return Vh(binding, fixedItemWidthDimenRes, fixedItemMarginDimenRes)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], onClick, onLongClick)

    override fun getItemCount(): Int = items.size

    class Vh(
        private val binding: ItemVideoCardBinding,
        private val fixedItemWidthDimenRes: Int?,
        private val fixedItemMarginDimenRes: Int?,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            applyFixedSizing()
        }

        fun bind(item: VideoCard, onClick: (VideoCard, Int) -> Unit, onLongClick: ((VideoCard, Int) -> Boolean)?) {
            binding.tvTitle.text = item.title
            binding.tvSubtitle.text =
                item.pubDateText
                    ?: if (item.ownerName.isBlank()) "" else "UP ${item.ownerName}"
            val pubDateText = item.pubDate?.let { Format.pubDateText(it) }.orEmpty()
            binding.tvPubdate.text = pubDateText
            binding.tvPubdate.isVisible = pubDateText.isNotBlank()
            binding.tvDuration.text = Format.duration(item.durationSec)
            binding.tvView.text = Format.count(item.view)
            binding.tvDanmaku.text = Format.count(item.danmaku)
            binding.tvChargeBadge.isVisible = item.isChargingArc
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))

            applyOverlayTranslations()

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onClick(item, pos)
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnLongClickListener false
                onLongClick?.invoke(item, pos) ?: false
            }
        }

        private fun applyFixedSizing() {
            if (fixedItemWidthDimenRes != null) {
                val w =
                    binding.root.resources
                        .getDimensionPixelSize(fixedItemWidthDimenRes)
                        .coerceAtLeast(1)
                val lp = binding.root.layoutParams
                if (lp != null && lp.width != w) {
                    lp.width = w
                    binding.root.layoutParams = lp
                }
            }

            if (fixedItemMarginDimenRes != null) {
                val margin = binding.root.resources.getDimensionPixelSize(fixedItemMarginDimenRes).coerceAtLeast(0)
                (binding.root.layoutParams as? MarginLayoutParams)?.let { lp ->
                    if (lp.leftMargin != margin || lp.topMargin != margin || lp.rightMargin != margin || lp.bottomMargin != margin) {
                        lp.setMargins(margin, margin, margin, margin)
                        binding.root.layoutParams = lp
                    }
                }
            }
        }

        private fun applyOverlayTranslations() {
            val res = binding.root.resources
            val textMargin = res.getDimensionPixelSize(R.dimen.video_card_text_margin)
            val padH = res.getDimensionPixelSize(R.dimen.video_card_duration_padding_h)
            val padV = res.getDimensionPixelSize(R.dimen.video_card_duration_padding_v)

            // "Closer to the left edge by 50%": the visible inset is (margin + inner padding),
            // so shift left by 50% of that total inset (more noticeable than margin-only).
            val insetX = textMargin + padH
            val shiftX = -insetX * 0.5f
            binding.llStats.translationX = shiftX
            binding.tvDuration.translationX = shiftX

            // "Move down 20%": apply 20% of overlay height (and a tiny baseline from margin)
            // so the change is visible across different UI scales.
            fun applyShiftY() {
                val overlayH = maxOf(binding.llStats.height, binding.tvDuration.height)
                val shiftY = (textMargin + padV) * 0.2f + overlayH * 0.2f
                binding.llStats.translationY = shiftY
                binding.tvDuration.translationY = shiftY
            }

            if (binding.llStats.height > 0 || binding.tvDuration.height > 0) {
                applyShiftY()
            } else {
                binding.root.post { applyShiftY() }
            }
        }
    }
}
