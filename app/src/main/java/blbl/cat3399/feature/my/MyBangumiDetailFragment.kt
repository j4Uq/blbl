package blbl.cat3399.feature.my

import android.os.Bundle
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiEpisode
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.setPaddingIfChanged
import blbl.cat3399.core.ui.setTextSizePxIfChanged
import blbl.cat3399.core.ui.uiScaler
import blbl.cat3399.core.ui.postDelayedIfAlive
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.FragmentMyBangumiDetailBinding
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MyBangumiDetailFragment : Fragment(), RefreshKeyHandler {
    private var _binding: FragmentMyBangumiDetailBinding? = null
    private val binding get() = _binding!!

    private val seasonIdArg: Long? by lazy { requireArguments().getLong(ARG_SEASON_ID, -1L).takeIf { it > 0L } }
    private val epIdArg: Long? by lazy { requireArguments().getLong(ARG_EP_ID, -1L).takeIf { it > 0L } }
    private val isDrama: Boolean by lazy { requireArguments().getBoolean(ARG_IS_DRAMA) }
    private val continueEpIdArg: Long? by lazy { requireArguments().getLong(ARG_CONTINUE_EP_ID, -1L).takeIf { it > 0 } }
    private val continueEpIndexArg: Int? by lazy { requireArguments().getInt(ARG_CONTINUE_EP_INDEX, -1).takeIf { it > 0 } }

    private lateinit var epAdapter: BangumiEpisodeAdapter
    private var currentEpisodes: List<BangumiEpisode> = emptyList()
    private var continueEpisode: BangumiEpisode? = null
    private var episodeOrderReversed: Boolean = false
    private var lastAppliedSizingScale: Float? = null
    private var resolvedSeasonId: Long? = null
    private var pendingAutoFocusFirstEpisode: Boolean = true
    private var autoFocusAttempts: Int = 0
    private var epDataObserver: RecyclerView.AdapterDataObserver? = null
    private var pendingAutoFocusPrimary: Boolean = true
    private var loadJob: Job? = null
    private var episodeChildAttachListener: RecyclerView.OnChildAttachStateChangeListener? = null

    private var baseCoverSize: IntArray? = null
    private var baseCoverMargins: IntArray? = null

    private var baseTitleMargins: IntArray? = null
    private var baseTitleTextSizePx: Float? = null
    private var baseMetaMargins: IntArray? = null
    private var baseMetaTextSizePx: Float? = null
    private var baseDescMargins: IntArray? = null
    private var baseDescTextSizePx: Float? = null

    private var basePrimaryMargins: IntArray? = null
    private var basePrimaryTextSizePx: Float? = null
    private var baseSecondaryMargins: IntArray? = null
    private var baseSecondaryTextSizePx: Float? = null

    private var baseEpisodeHeaderMargins: IntArray? = null
    private var baseEpisodeHeaderTextSizePx: Float? = null

    private var baseEpisodeOrderHeight: Int? = null
    private var baseEpisodeOrderMargins: IntArray? = null
    private var baseEpisodeOrderRadiusPx: Float? = null
    private var baseEpisodeOrderStrokeWidthPx: Int? = null
    private var baseEpisodeOrderContentPadding: IntArray? = null
    private var baseEpisodeOrderIconSize: Int? = null
    private var baseEpisodeOrderIconMarginEnd: Int? = null
    private var baseEpisodeOrderTextSizePx: Float? = null

    private var baseEpisodeRecyclerPadding: IntArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        episodeOrderReversed = BiliClient.prefs.pgcEpisodeOrderReversed
        pendingAutoFocusFirstEpisode = savedInstanceState == null
        pendingAutoFocusPrimary = savedInstanceState == null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyBangumiDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener {
            val popped = parentFragmentManager.popBackStackImmediate()
            if (!popped) activity?.finish()
        }
        binding.btnSecondary.text = if (isDrama) "已追剧" else "已追番"
        applyBackButtonSizing()
        applyHeaderSizing(uiScale = UiScale.factor(requireContext()))

        updateEpisodeOrderUi()
        binding.btnEpisodeOrder.setOnClickListener {
            episodeOrderReversed = !episodeOrderReversed
            BiliClient.prefs.pgcEpisodeOrderReversed = episodeOrderReversed
            updateEpisodeOrderUi()
            submitEpisodes()
            // After switching order, always land on the first visible card in the new order.
            if (!focusEpisodeById(epId = null, fallbackPosition = 0)) {
                binding.btnEpisodeOrder.requestFocus()
            }
        }

        epAdapter =
            BangumiEpisodeAdapter { ep, pos ->
                playEpisode(ep, pos)
            }
        binding.recyclerEpisodes.adapter = epAdapter
        binding.recyclerEpisodes.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        epDataObserver =
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    tryAutoFocusFirstEpisode()
                    tryAutoFocusPrimary()
                }
            }.also { epAdapter.registerAdapterDataObserver(it) }

        installEpisodeFocusHandlers()
        installButtonFocusHandlers()

        binding.btnPrimary.setOnClickListener {
            val ep = continueEpisode ?: currentEpisodes.firstOrNull()
            if (ep == null) {
                AppToast.show(requireContext(), "暂无可播放剧集")
                return@setOnClickListener
            }
            val pos = currentEpisodes.indexOfFirst { it.epId == ep.epId }.takeIf { it >= 0 } ?: 0
            playEpisode(ep, pos)
        }
        binding.btnSecondary.setOnClickListener {
            AppToast.show(requireContext(), "暂不支持操作")
        }
    }

    override fun onResume() {
        super.onResume()
        applyBackButtonSizing()
        tryAutoFocusPrimary()
        load()
    }

    private fun applyBackButtonSizing() {
        val b = _binding ?: return
        val sidebarScale = UiScale.factor(requireContext())
        BackButtonSizingHelper.applySidebarSizing(
            view = b.btnBack,
            resources = b.root.resources,
            sidebarScale = sidebarScale,
        )
    }

    private fun applyHeaderSizing(uiScale: Float) {
        val b = _binding ?: return
        val scale = uiScale.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        if (lastAppliedSizingScale == scale) return

        val scaler = requireContext().uiScaler(scale)
        fun scaled(valuePx: Int, minPx: Int = 0): Int = scaler.scaledPx(valuePx, minPx = minPx)
        fun scaledF(valuePx: Float, minPx: Float = 0f): Float = scaler.scaledPxF(valuePx, minPx = minPx)

        b.ivCover.layoutParams?.let { lp ->
            val base =
                baseCoverSize
                    ?: intArrayOf(lp.width, lp.height).also { baseCoverSize = it }
            val w = scaled(base[0], minPx = 1)
            val h = scaled(base[1], minPx = 1)
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                b.ivCover.layoutParams = lp
            }
        }
        (b.ivCover.layoutParams as? MarginLayoutParams)?.let { lp ->
            val base =
                baseCoverMargins
                    ?: intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin).also { baseCoverMargins = it }
            val ms = scaled(base[0]).coerceAtLeast(0)
            val mt = scaled(base[1]).coerceAtLeast(0)
            val me = scaled(base[2]).coerceAtLeast(0)
            val mb = scaled(base[3]).coerceAtLeast(0)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                b.ivCover.layoutParams = lp
            }
        }

        (b.tvTitle.layoutParams as? MarginLayoutParams)?.let { lp ->
            val base =
                baseTitleMargins
                    ?: intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin).also { baseTitleMargins = it }
            val ms = scaled(base[0]).coerceAtLeast(0)
            val mt = scaled(base[1]).coerceAtLeast(0)
            val me = scaled(base[2]).coerceAtLeast(0)
            val mb = scaled(base[3]).coerceAtLeast(0)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                b.tvTitle.layoutParams = lp
            }
        }
        val baseTitleTs = baseTitleTextSizePx ?: b.tvTitle.textSize.also { baseTitleTextSizePx = it }
        b.tvTitle.setTextSizePxIfChanged(scaledF(baseTitleTs, minPx = 1f))

        (b.tvMeta.layoutParams as? MarginLayoutParams)?.let { lp ->
            val base =
                baseMetaMargins
                    ?: intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin).also { baseMetaMargins = it }
            val ms = scaled(base[0]).coerceAtLeast(0)
            val mt = scaled(base[1]).coerceAtLeast(0)
            val me = scaled(base[2]).coerceAtLeast(0)
            val mb = scaled(base[3]).coerceAtLeast(0)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                b.tvMeta.layoutParams = lp
            }
        }
        val baseMetaTs = baseMetaTextSizePx ?: b.tvMeta.textSize.also { baseMetaTextSizePx = it }
        b.tvMeta.setTextSizePxIfChanged(scaledF(baseMetaTs, minPx = 1f))

        (b.tvDesc.layoutParams as? MarginLayoutParams)?.let { lp ->
            val base =
                baseDescMargins
                    ?: intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin).also { baseDescMargins = it }
            val ms = scaled(base[0]).coerceAtLeast(0)
            val mt = scaled(base[1]).coerceAtLeast(0)
            val me = scaled(base[2]).coerceAtLeast(0)
            val mb = scaled(base[3]).coerceAtLeast(0)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                b.tvDesc.layoutParams = lp
            }
        }
        val baseDescTs = baseDescTextSizePx ?: b.tvDesc.textSize.also { baseDescTextSizePx = it }
        b.tvDesc.setTextSizePxIfChanged(scaledF(baseDescTs, minPx = 1f))

        (b.btnPrimary.layoutParams as? MarginLayoutParams)?.let { lp ->
            val base =
                basePrimaryMargins
                    ?: intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin).also { basePrimaryMargins = it }
            val ms = scaled(base[0]).coerceAtLeast(0)
            val mt = scaled(base[1]).coerceAtLeast(0)
            val me = scaled(base[2]).coerceAtLeast(0)
            val mb = scaled(base[3]).coerceAtLeast(0)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                b.btnPrimary.layoutParams = lp
            }
        }
        val basePrimaryTs = basePrimaryTextSizePx ?: b.btnPrimary.textSize.also { basePrimaryTextSizePx = it }
        b.btnPrimary.setTextSizePxIfChanged(scaledF(basePrimaryTs, minPx = 1f))

        (b.btnSecondary.layoutParams as? MarginLayoutParams)?.let { lp ->
            val base =
                baseSecondaryMargins
                    ?: intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin).also { baseSecondaryMargins = it }
            val ms = scaled(base[0]).coerceAtLeast(0)
            val mt = scaled(base[1]).coerceAtLeast(0)
            val me = scaled(base[2]).coerceAtLeast(0)
            val mb = scaled(base[3]).coerceAtLeast(0)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                b.btnSecondary.layoutParams = lp
            }
        }
        val baseSecondaryTs = baseSecondaryTextSizePx ?: b.btnSecondary.textSize.also { baseSecondaryTextSizePx = it }
        b.btnSecondary.setTextSizePxIfChanged(scaledF(baseSecondaryTs, minPx = 1f))

        (b.tvEpisodeHeader.layoutParams as? MarginLayoutParams)?.let { lp ->
            val base =
                baseEpisodeHeaderMargins
                    ?: intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin).also { baseEpisodeHeaderMargins = it }
            val ms = scaled(base[0]).coerceAtLeast(0)
            val mt = scaled(base[1]).coerceAtLeast(0)
            val me = scaled(base[2]).coerceAtLeast(0)
            val mb = scaled(base[3]).coerceAtLeast(0)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                b.tvEpisodeHeader.layoutParams = lp
            }
        }
        val baseHeaderTs = baseEpisodeHeaderTextSizePx ?: b.tvEpisodeHeader.textSize.also { baseEpisodeHeaderTextSizePx = it }
        b.tvEpisodeHeader.setTextSizePxIfChanged(scaledF(baseHeaderTs, minPx = 1f))

        (b.btnEpisodeOrder.layoutParams as? MarginLayoutParams)?.let { lp ->
            val base =
                baseEpisodeOrderMargins
                    ?: intArrayOf(lp.marginStart, lp.topMargin, lp.marginEnd, lp.bottomMargin).also { baseEpisodeOrderMargins = it }
            val ms = scaled(base[0]).coerceAtLeast(0)
            val mt = scaled(base[1]).coerceAtLeast(0)
            val me = scaled(base[2]).coerceAtLeast(0)
            val mb = scaled(base[3]).coerceAtLeast(0)
            if (lp.marginStart != ms || lp.topMargin != mt || lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.topMargin = mt
                lp.marginEnd = me
                lp.bottomMargin = mb
                b.btnEpisodeOrder.layoutParams = lp
            }
        }
        b.btnEpisodeOrder.layoutParams?.let { lp ->
            val baseH = baseEpisodeOrderHeight ?: lp.height.takeIf { it > 0 }?.also { baseEpisodeOrderHeight = it }
            if (baseH != null) {
                val h = scaled(baseH, minPx = 1)
                if (lp.height != h) {
                    lp.height = h
                    b.btnEpisodeOrder.layoutParams = lp
                }
            }
        }
        val baseRadius = baseEpisodeOrderRadiusPx ?: b.btnEpisodeOrder.radius.also { baseEpisodeOrderRadiusPx = it }
        val radius = scaledF(baseRadius).coerceAtLeast(0f)
        if (b.btnEpisodeOrder.radius != radius) b.btnEpisodeOrder.radius = radius
        val baseStroke = baseEpisodeOrderStrokeWidthPx ?: b.btnEpisodeOrder.strokeWidth.also { baseEpisodeOrderStrokeWidthPx = it }
        val stroke = scaled(baseStroke).coerceAtLeast(0)
        if (b.btnEpisodeOrder.strokeWidth != stroke) b.btnEpisodeOrder.strokeWidth = stroke

        val content = b.episodeOrderContent
        val basePad =
            baseEpisodeOrderContentPadding
                ?: intArrayOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom).also { baseEpisodeOrderContentPadding = it }
        content.setPaddingIfChanged(
            scaled(basePad[0]).coerceAtLeast(0),
            scaled(basePad[1]).coerceAtLeast(0),
            scaled(basePad[2]).coerceAtLeast(0),
            scaled(basePad[3]).coerceAtLeast(0),
        )

        b.ivEpisodeOrderIcon.layoutParams?.let { lp ->
            val base = baseEpisodeOrderIconSize ?: lp.width.takeIf { it > 0 }?.also { baseEpisodeOrderIconSize = it }
            if (base != null) {
                val size = scaled(base, minPx = 1)
                if (lp.width != size || lp.height != size) {
                    lp.width = size
                    lp.height = size
                    b.ivEpisodeOrderIcon.layoutParams = lp
                }
            }
        }
        (b.ivEpisodeOrderIcon.layoutParams as? MarginLayoutParams)?.let { lp ->
            val base = baseEpisodeOrderIconMarginEnd ?: lp.marginEnd.also { baseEpisodeOrderIconMarginEnd = it }
            val me = scaled(base).coerceAtLeast(0)
            if (lp.marginEnd != me) {
                lp.marginEnd = me
                b.ivEpisodeOrderIcon.layoutParams = lp
            }
        }

        val baseOrderTs = baseEpisodeOrderTextSizePx ?: b.tvEpisodeOrder.textSize.also { baseEpisodeOrderTextSizePx = it }
        b.tvEpisodeOrder.setTextSizePxIfChanged(scaledF(baseOrderTs, minPx = 1f))

        val recycler = b.recyclerEpisodes
        val baseRecyclerPad =
            baseEpisodeRecyclerPadding
                ?: intArrayOf(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, recycler.paddingBottom).also { baseEpisodeRecyclerPadding = it }
        recycler.setPaddingIfChanged(
            scaled(baseRecyclerPad[0]).coerceAtLeast(0),
            scaled(baseRecyclerPad[1]).coerceAtLeast(0),
            scaled(baseRecyclerPad[2]).coerceAtLeast(0),
            scaled(baseRecyclerPad[3]).coerceAtLeast(0),
        )

        lastAppliedSizingScale = scale
    }

    override fun handleRefreshKey(): Boolean {
        if (!isResumed) return false
        if (_binding == null) return false
        load()
        return true
    }

    private fun tryAutoFocusPrimary() {
        if (!pendingAutoFocusPrimary) return
        if (!isResumed) return
        val b = _binding ?: return
        // If we have episodes and no "continue" target, prefer episode list focus.
        if (continueEpisode == null && this::epAdapter.isInitialized && epAdapter.itemCount > 0) {
            pendingAutoFocusPrimary = false
            return
        }
        val focused = activity?.currentFocus
        if (focused != null && FocusTreeUtils.isDescendantOf(focused, b.root) && focused != b.btnBack) {
            pendingAutoFocusPrimary = false
            return
        }
        val isUiAlive = { _binding === b && isResumed }
        b.root.postIfAlive(isAlive = isUiAlive) {
            if (!pendingAutoFocusPrimary) return@postIfAlive
            val focused2 = activity?.currentFocus
            if (focused2 != null && FocusTreeUtils.isDescendantOf(focused2, b.root) && focused2 != b.btnBack) {
                pendingAutoFocusPrimary = false
                return@postIfAlive
            }
            if (b.btnPrimary.requestFocus()) {
                pendingAutoFocusPrimary = false
            }
        }
    }

    private fun tryAutoFocusFirstEpisode() {
        if (!pendingAutoFocusFirstEpisode) return
        if (!isResumed) return
        val b = _binding ?: return
        if (!this::epAdapter.isInitialized) return
        if (epAdapter.itemCount <= 0) return

        val recycler = b.recyclerEpisodes
        val focused = activity?.currentFocus
        if (focused != null && FocusTreeUtils.isDescendantOf(focused, recycler)) {
            pendingAutoFocusFirstEpisode = false
            return
        }
        // Don't steal focus if user has already moved inside this page.
        if (focused != null && FocusTreeUtils.isDescendantOf(focused, b.root) && focused != b.btnBack && focused != b.btnPrimary) {
            pendingAutoFocusFirstEpisode = false
            return
        }

        autoFocusAttempts++
        if (autoFocusAttempts > 60) {
            pendingAutoFocusFirstEpisode = false
            return
        }

        val isUiAlive = { _binding === b && isResumed }
        recycler.postIfAlive(isAlive = isUiAlive) {
            if (!pendingAutoFocusFirstEpisode) return@postIfAlive
            if (epAdapter.itemCount <= 0) return@postIfAlive

            val r = recycler
            val targetEpId = continueEpisode?.epId
            val targetPos =
                targetEpId?.let { id ->
                    orderedEpisodes().indexOfFirst { it.epId == id }.takeIf { it >= 0 }
                } ?: 0
            val safeTargetPos = targetPos.coerceIn(0, epAdapter.itemCount - 1)
            val focused2 = activity?.currentFocus
            if (focused2 != null && FocusTreeUtils.isDescendantOf(focused2, r)) {
                pendingAutoFocusFirstEpisode = false
                return@postIfAlive
            }

            val success = r.findViewHolderForAdapterPosition(safeTargetPos)?.itemView?.requestFocus() == true
            if (success) {
                pendingAutoFocusFirstEpisode = false
                return@postIfAlive
            }

            r.scrollToPosition(safeTargetPos)
            r.postDelayedIfAlive(delayMillis = 16, isAlive = isUiAlive) { tryAutoFocusFirstEpisode() }
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob =
            viewLifecycleOwner.lifecycleScope.launch {
            try {
                val seasonId = seasonIdArg
                val epId = epIdArg
                if (seasonId == null && epId == null) error("缺少 seasonId/epId")
                val detail =
                    if (seasonId != null) {
                        BiliApi.bangumiSeasonDetail(seasonId = seasonId)
                    } else {
                        BiliApi.bangumiSeasonDetailByEpId(epId = epId ?: 0L)
                    }
                resolvedSeasonId = detail.seasonId.takeIf { it > 0L } ?: seasonId
                val b = _binding ?: return@launch
                b.tvTitle.text = detail.title
                b.tvDesc.text = detail.evaluate.orEmpty()

                val metaParts = buildList {
                    detail.subtitle?.takeIf { it.isNotBlank() }?.let { add(it) }
                    detail.ratingScore?.let { add(String.format("%.1f分", it)) }
                    detail.views?.let { add("${Format.count(it)}次观看") }
                    detail.danmaku?.let { add("${Format.count(it)}条弹幕") }
                }
                b.tvMeta.text = metaParts.joinToString(" | ")
                ImageLoader.loadInto(b.ivCover, ImageUrl.poster(detail.coverUrl))
                currentEpisodes = normalizeEpisodeOrder(detail.episodes)
                continueEpisode =
                    (continueEpIdArg ?: detail.progressLastEpId)?.let { id ->
                        detail.episodes.firstOrNull { it.epId == id }
                    } ?: continueEpIndexArg?.let { idx ->
                        detail.episodes.firstOrNull { it.title.trim() == idx.toString() }
                    }
                submitEpisodes()
                tryAutoFocusFirstEpisode()
                tryAutoFocusPrimary()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("MyBangumiDetail", "load failed seasonId=${seasonIdArg ?: -1L} epId=${epIdArg ?: -1L}", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            }
        }
    }

    private fun updateEpisodeOrderUi() {
        val b = _binding ?: return
        b.tvEpisodeOrder.text =
            getString(
                if (episodeOrderReversed) {
                    blbl.cat3399.R.string.my_episode_order_desc
                } else {
                    blbl.cat3399.R.string.my_episode_order_asc
                },
            )
    }

    private fun orderedEpisodes(): List<BangumiEpisode> = if (episodeOrderReversed) currentEpisodes.asReversed() else currentEpisodes

    private fun normalizeEpisodeOrder(list: List<BangumiEpisode>): List<BangumiEpisode> {
        if (list.size <= 1) return list

        data class Entry(
            val episode: BangumiEpisode,
            val hasNumber: Boolean,
            val number: Double,
            val originalIndex: Int,
        )

        val entries =
            list.mapIndexed { index, ep ->
                val num =
                    parseEpisodeNumber(ep.title)
                        ?: parseEpisodeNumber(ep.longTitle)
                Entry(
                    episode = ep,
                    hasNumber = num != null,
                    number = num ?: 0.0,
                    originalIndex = index,
                )
            }

        // Keep non-numeric items grouped after numeric episodes, while preserving their relative order.
        return entries
            .sortedWith(compareBy<Entry>({ !it.hasNumber }, { it.number }, { it.originalIndex }))
            .map { it.episode }
    }

    private fun parseEpisodeNumber(raw: String?): Double? {
        val s = raw?.trim().orEmpty()
        if (s.isBlank()) return null
        s.toDoubleOrNull()?.let { return it }

        // Handle titles like "第12话", "12.5", "EP12" etc.
        val match = EP_NUMBER_REGEX.find(s) ?: return null
        return match.value.toDoubleOrNull()
    }

    private fun submitEpisodes(anchorEpId: Long? = null) {
        if (!this::epAdapter.isInitialized) return
        val b = _binding ?: return
        val list = orderedEpisodes()
        epAdapter.submit(list)

        if (anchorEpId == null) return
        val index = list.indexOfFirst { it.epId == anchorEpId }.takeIf { it >= 0 } ?: return
        b.recyclerEpisodes.postIfAlive(isAlive = { _binding === b }) {
            b.recyclerEpisodes.scrollToPosition(index)
        }
    }

    private fun scrollEpisodeToPosition(position: Int, requestFocus: Boolean) {
        val b = _binding ?: return
        val recycler = b.recyclerEpisodes
        val isViewAlive = { _binding === b }
        recycler.postIfAlive(isAlive = isViewAlive) {
            recycler.scrollToPosition(position)
            if (!requestFocus) return@postIfAlive
            requestEpisodeFocus(position = position, attempt = 0)
        }
    }

    private fun requestEpisodeFocus(position: Int, attempt: Int) {
        val b = _binding ?: return
        val recycler = b.recyclerEpisodes
        val isUiAlive = { _binding === b && isResumed }
        recycler.postIfAlive(isAlive = isUiAlive) {
            val view = recycler.findViewHolderForAdapterPosition(position)?.itemView
            if (view?.requestFocus() == true) return@postIfAlive

            if (attempt >= 30) return@postIfAlive
            recycler.scrollToPosition(position)
            recycler.postDelayedIfAlive(delayMillis = 16, isAlive = isUiAlive) { requestEpisodeFocus(position = position, attempt = attempt + 1) }
        }
    }

    private fun focusEpisodeById(epId: Long?, fallbackPosition: Int = 0): Boolean {
        if (!this::epAdapter.isInitialized) return false
        val list = orderedEpisodes()
        if (list.isEmpty()) return false

        val pos =
            epId?.let { id -> list.indexOfFirst { it.epId == id }.takeIf { it >= 0 } }
                ?: fallbackPosition.coerceIn(0, list.size - 1)
        scrollEpisodeToPosition(position = pos, requestFocus = true)
        return true
    }

    private fun focusFirstEpisodeCard(): Boolean = focusEpisodeById(epId = null, fallbackPosition = 0)

    private fun installButtonFocusHandlers() {
        val b = _binding ?: return

        b.btnBack.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // Don't allow UP to escape to sidebar/global UI.
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    b.btnPrimary.requestFocus()
                    true
                }

                else -> false
            }
        }

        fun handleDownFromActionButtons(): Boolean {
            if (focusFirstEpisodeCard()) return true
            b.btnEpisodeOrder.requestFocus()
            return true
        }

        b.btnPrimary.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    b.btnBack.requestFocus()
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> handleDownFromActionButtons()
                else -> false
            }
        }
        b.btnSecondary.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    b.btnBack.requestFocus()
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> handleDownFromActionButtons()
                else -> false
            }
        }

        b.btnEpisodeOrder.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // Enter episode list; if empty, consume so we don't escape to sidebar.
                    focusFirstEpisodeCard()
                    true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    b.btnPrimary.requestFocus()
                    true
                }

                else -> false
            }
        }
    }

    private fun installEpisodeFocusHandlers() {
        val b = _binding ?: return
        val recycler = b.recyclerEpisodes
        episodeChildAttachListener?.let(recycler::removeOnChildAttachStateChangeListener)

        episodeChildAttachListener =
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val holder = recycler.findContainingViewHolder(view)
                        val pos =
                            holder?.bindingAdapterPosition
                                ?.takeIf { it != RecyclerView.NO_POSITION }
                        val total = recycler.adapter?.itemCount ?: 0
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                b.btnEpisodeOrder.requestFocus()
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                // Bottom edge: don't escape to sidebar/global UI.
                                val next = view.focusSearch(View.FOCUS_DOWN)
                                next == null || !FocusTreeUtils.isDescendantOf(next, b.root)
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                // Keep focus inside the episode list when holding RIGHT.
                                // We always consume the key here; letting the system perform a global focus search
                                // can occasionally jump outside of this RecyclerView when keys are repeated.
                                if (pos == null || total <= 0) return@setOnKeyListener false
                                if (pos >= total - 1) return@setOnKeyListener true

                                val itemView = recycler.findContainingItemView(view) ?: view
                                val next =
                                    FocusFinder.getInstance().findNextFocus(recycler, itemView, View.FOCUS_RIGHT)
                                if (next != null && FocusTreeUtils.isDescendantOf(next, recycler)) {
                                    if (next.requestFocus()) return@setOnKeyListener true
                                }

                                // The next card may not be laid out yet; scroll a bit to force layout and keep
                                // focus stable inside the list, then request focus by adapter position.
                                if (recycler.canScrollHorizontally(1)) {
                                    val dx = (itemView.width * 0.8f).roundToInt().coerceAtLeast(1)
                                    recycler.scrollBy(dx, 0)
                                }
                                val isUiAlive = { _binding === b && isResumed }
                                recycler.postIfAlive(isAlive = isUiAlive) { requestEpisodeFocus(position = pos + 1, attempt = 0) }
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                // Allow LEFT from the first card to escape (e.g. to sidebar), otherwise keep in list.
                                if (pos == null || total <= 0) return@setOnKeyListener false
                                if (pos <= 0) return@setOnKeyListener false

                                val itemView = recycler.findContainingItemView(view) ?: view
                                val next =
                                    FocusFinder.getInstance().findNextFocus(recycler, itemView, View.FOCUS_LEFT)
                                if (next != null && FocusTreeUtils.isDescendantOf(next, recycler)) {
                                    if (next.requestFocus()) return@setOnKeyListener true
                                }

                                if (recycler.canScrollHorizontally(-1)) {
                                    val dx = (itemView.width * 0.8f).roundToInt().coerceAtLeast(1)
                                    recycler.scrollBy(-dx, 0)
                                }
                                val isUiAlive = { _binding === b && isResumed }
                                recycler.postIfAlive(isAlive = isUiAlive) { requestEpisodeFocus(position = pos - 1, attempt = 0) }
                                true
                            }

                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                    view.onFocusChangeListener = null
                }
            }.also(recycler::addOnChildAttachStateChangeListener)
    }

    private fun playEpisode(ep: BangumiEpisode, pos: Int) {
        val seasonId = resolvedSeasonId ?: seasonIdArg
        if (seasonId == null || seasonId <= 0L) {
            AppToast.show(requireContext(), "缺少 seasonId")
            return
        }
        val bvid = ep.bvid.orEmpty()
        val cid = ep.cid ?: -1L
        if (bvid.isBlank() || cid <= 0) {
            AppToast.show(requireContext(), "缺少播放信息（bvid/cid）")
            return
        }
        val allItems =
            currentEpisodes.map {
                PlayerPlaylistItem(
                    bvid = it.bvid.orEmpty(),
                    cid = it.cid,
                    epId = it.epId,
                    aid = it.aid,
                    title = it.title,
                )
            }
        val playlistItems = allItems.filter { it.bvid.isNotBlank() }
        val playlistIndex =
            playlistItems.indexOfFirst { it.epId == ep.epId }
                .takeIf { it >= 0 }
                ?: pos
        val playlistCards =
            currentEpisodes
                .filter { !it.bvid.isNullOrBlank() }
                .mapIndexed { index, item ->
                    VideoCard(
                        bvid = item.bvid.orEmpty(),
                        cid = item.cid,
                        aid = item.aid,
                        epId = item.epId,
                        title = item.title.takeIf { it.isNotBlank() } ?: "第${index + 1}集",
                        coverUrl = item.coverUrl.orEmpty(),
                        durationSec = 0,
                        ownerName = "",
                        ownerFace = null,
                        ownerMid = null,
                        view = null,
                        danmaku = null,
                        pubDate = null,
                        pubDateText = null,
                    )
                }
        val token =
            PlayerPlaylistStore.put(
                items = playlistItems,
                index = playlistIndex,
                source = "Bangumi:$seasonId",
                uiCards = playlistCards,
            )
        startActivity(
            Intent(requireContext(), PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_BVID, bvid)
                .putExtra(PlayerActivity.EXTRA_CID, cid)
                .putExtra(PlayerActivity.EXTRA_EP_ID, ep.epId)
                .apply { ep.aid?.let { putExtra(PlayerActivity.EXTRA_AID, it) } }
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, playlistIndex),
        )
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        if (this::epAdapter.isInitialized) {
            epDataObserver?.let { epAdapter.unregisterAdapterDataObserver(it) }
        }
        epDataObserver = null
        episodeChildAttachListener?.let { l ->
            _binding?.recyclerEpisodes?.removeOnChildAttachStateChangeListener(l)
        }
        episodeChildAttachListener = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SEASON_ID = "season_id"
        private const val ARG_EP_ID = "ep_id"
        private const val ARG_IS_DRAMA = "is_drama"
        private const val ARG_CONTINUE_EP_ID = "continue_ep_id"
        private const val ARG_CONTINUE_EP_INDEX = "continue_ep_index"
        private val EP_NUMBER_REGEX = Regex("(\\d+(?:\\.\\d+)?)")

        fun newInstance(
            seasonId: Long,
            isDrama: Boolean,
            continueEpId: Long?,
            continueEpIndex: Int?,
        ): MyBangumiDetailFragment =
            MyBangumiDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_SEASON_ID, seasonId)
                    putBoolean(ARG_IS_DRAMA, isDrama)
                    continueEpId?.let { putLong(ARG_CONTINUE_EP_ID, it) }
                    continueEpIndex?.let { putInt(ARG_CONTINUE_EP_INDEX, it) }
                }
            }

        fun newInstanceByEpId(
            epId: Long,
            isDrama: Boolean,
            continueEpId: Long?,
            continueEpIndex: Int?,
        ): MyBangumiDetailFragment =
            MyBangumiDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_EP_ID, epId)
                    putBoolean(ARG_IS_DRAMA, isDrama)
                    continueEpId?.let { putLong(ARG_CONTINUE_EP_ID, it) }
                    continueEpIndex?.let { putInt(ARG_CONTINUE_EP_INDEX, it) }
                }
            }
    }
}
