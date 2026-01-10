package blbl.cat3399.feature.settings

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.TvMode
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var currentSectionIndex: Int = -1
    private lateinit var leftAdapter: SettingsLeftAdapter
    private lateinit var rightAdapter: SettingsEntryAdapter

    private val sections = listOf(
        "通用设置",
        "页面设置",
        "播放设置",
        "弹幕设置",
        "关于应用",
        "设备信息",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        binding.btnBack.setOnClickListener { finish() }

        leftAdapter = SettingsLeftAdapter { index -> showSection(index, keepScroll = false) }
        binding.recyclerLeft.layoutManager = LinearLayoutManager(this)
        binding.recyclerLeft.adapter = leftAdapter
        leftAdapter.submit(sections, selected = 0)

        binding.recyclerRight.layoutManager = LinearLayoutManager(this)
        binding.recyclerRight.itemAnimator = null
        rightAdapter = SettingsEntryAdapter { entry -> onEntryClicked(entry) }
        binding.recyclerRight.adapter = rightAdapter
        showSection(0)
    }

    override fun onResume() {
        super.onResume()
        applyUiMode()
        ensureInitialFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyUiMode() {
        val tvMode = TvMode.isEnabled(this)
        val widthDp = if (tvMode) 320f else 220f
        val widthPx = dp(widthDp)
        val lp = binding.recyclerLeft.layoutParams
        if (lp.width != widthPx) {
            lp.width = widthPx
            binding.recyclerLeft.layoutParams = lp
        }
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        binding.recyclerLeft.post {
            binding.recyclerLeft.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun dp(valueDp: Float): Int {
        val dm = resources.displayMetrics
        return (valueDp * dm.density).toInt()
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }

    private fun showSection(index: Int, keepScroll: Boolean = index == currentSectionIndex, focusTitle: String? = null) {
        val lm = binding.recyclerRight.layoutManager as? LinearLayoutManager
        val firstVisible = if (keepScroll) lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION else RecyclerView.NO_POSITION
        val topOffset =
            if (keepScroll && firstVisible != RecyclerView.NO_POSITION) {
                (binding.recyclerRight.getChildAt(0)?.top ?: 0) - binding.recyclerRight.paddingTop
            } else {
                0
            }
        currentSectionIndex = index

        val prefs = BiliClient.prefs
        val entries = when (sections.getOrNull(index)) {
            "通用设置" -> listOf(
                SettingEntry("图片质量", prefs.imageQuality, "影响封面/头像 URL 追加参数"),
                SettingEntry("User-Agent", prefs.userAgent.take(60), "点击可编辑/重置（影响 CDN/接口风控）"),
                SettingEntry("清除登录", if (BiliClient.cookies.hasSessData()) "已登录" else "未登录", "清除 Cookie（SESSDATA 等）"),
            )

            "页面设置" -> listOf(
                SettingEntry("每行卡片数量", gridSpanText(prefs.gridSpanCount), "影响推荐/热门/分类（动态单独设置）"),
                SettingEntry("动态页每行卡片数量", gridSpanText(prefs.dynamicGridSpanCount), "默认 3"),
                SettingEntry("TV 模式", tvModeText(prefs.uiMode), "优化遥控器/键盘导航与焦点样式"),
                SettingEntry("以全屏模式运行", if (prefs.fullscreenEnabled) "开" else "关", "隐藏状态栏/导航栏"),
            )

            "播放设置" -> listOf(
                SettingEntry("默认画质", qnText(prefs.playerPreferredQn), "按 B 站 qn 清晰度选择（DASH 走轨道 id）"),
                SettingEntry("默认音轨", audioText(prefs.playerPreferredAudioId), "30280/30232/30216"),
                SettingEntry("默认播放速度", String.format(Locale.US, "%.2fx", prefs.playerSpeed), null),
                SettingEntry("字幕语言", subtitleLangText(prefs.subtitlePreferredLang), "自动/优先匹配"),
                SettingEntry("默认开启字幕", if (prefs.subtitleEnabledDefault) "开" else "关", "进入播放页时默认状态"),
                SettingEntry("视频编码", prefs.playerPreferredCodec, "AVC/HEVC/AV1"),
                SettingEntry("显示视频调试信息", if (prefs.playerDebugEnabled) "开" else "关", "播放器左上角调试框"),
                SettingEntry("播放结束双击返回", if (prefs.playerDoubleBackOnEnded) "开" else "关", "关=播放结束时按一次返回直接退出"),
            )

            "弹幕设置" -> listOf(
                SettingEntry("弹幕开关", if (prefs.danmakuEnabled) "开" else "关", null),
                SettingEntry("弹幕透明度", String.format("%.2f", prefs.danmakuOpacity), null),
                SettingEntry("弹幕字体大小", prefs.danmakuTextSizeSp.toInt().toString(), "sp"),
                SettingEntry("弹幕占屏比", areaText(prefs.danmakuArea), "1/4、1/2、3/4、不限"),
                SettingEntry("弹幕速度", prefs.danmakuSpeed.toString(), "1~10"),
                SettingEntry("跟随B站弹幕屏蔽", if (prefs.danmakuFollowBiliShield) "开" else "关", "需要登录且 dm/web/view 可用"),
                SettingEntry("智能云屏蔽", if (prefs.danmakuAiShieldEnabled) "开" else "关", "按弹幕 weight 过滤"),
                SettingEntry("智能云屏蔽等级", aiLevelText(prefs.danmakuAiShieldLevel), "0=默认(3)，1~10 越大越严格"),
                SettingEntry("允许滚动弹幕", if (prefs.danmakuAllowScroll) "开" else "关", null),
                SettingEntry("允许顶部悬停弹幕", if (prefs.danmakuAllowTop) "开" else "关", null),
                SettingEntry("允许底部悬停弹幕", if (prefs.danmakuAllowBottom) "开" else "关", null),
                SettingEntry("允许彩色弹幕", if (prefs.danmakuAllowColor) "开" else "关", "关=仅白色"),
                SettingEntry("允许特殊弹幕", if (prefs.danmakuAllowSpecial) "开" else "关", "高级/代码等（当前仅做过滤）"),
            )

            "关于应用" -> listOf(
                SettingEntry("版本", "0.1.0", null),
                SettingEntry("日志标签", "BLBL", "用于 Logcat 过滤"),
            )

            else -> listOf(
                SettingEntry("CPU", Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), null),
                SettingEntry("设备", "${Build.MANUFACTURER} ${Build.MODEL}", null),
                SettingEntry("系统", "Android ${Build.VERSION.RELEASE} API${Build.VERSION.SDK_INT}", null),
            )
        }
        rightAdapter.submit(entries)
        val focusIndex = focusTitle?.let { title -> entries.indexOfFirst { it.title == title } } ?: RecyclerView.NO_POSITION
        binding.recyclerRight.post {
            if (keepScroll && lm != null && firstVisible != RecyclerView.NO_POSITION) {
                lm.scrollToPositionWithOffset(firstVisible, topOffset)
            }
            if (focusIndex != RecyclerView.NO_POSITION) {
                val layoutManager = binding.recyclerRight.layoutManager as? LinearLayoutManager
                if (layoutManager != null) {
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()
                    if (focusIndex < first || focusIndex > last) {
                        layoutManager.scrollToPositionWithOffset(focusIndex, 0)
                    }
                }
                binding.recyclerRight.post {
                    binding.recyclerRight.findViewHolderForAdapterPosition(focusIndex)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun refreshSection(focusTitle: String? = null) {
        showSection(currentSectionIndex, focusTitle = focusTitle)
    }

    private fun onEntryClicked(entry: SettingEntry) {
        val prefs = BiliClient.prefs
        when (entry.title) {
            "图片质量" -> {
                val next = when (prefs.imageQuality) {
                    "small" -> "medium"
                    "medium" -> "large"
                    else -> "small"
                }
                prefs.imageQuality = next
                Toast.makeText(this, "图片质量：$next", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "User-Agent" -> showUserAgentDialog(currentSectionIndex, entry.title)
            "清除登录" -> showClearLoginDialog(currentSectionIndex, entry.title)

            "以全屏模式运行" -> {
                prefs.fullscreenEnabled = !prefs.fullscreenEnabled
                Immersive.apply(this, prefs.fullscreenEnabled)
                Toast.makeText(this, "全屏：${if (prefs.fullscreenEnabled) "开" else "关"}", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "TV 模式" -> {
                val options = listOf("自动", "开", "关")
                showChoiceDialog(
                    title = "TV 模式",
                    items = options,
                    current = tvModeText(prefs.uiMode),
                ) { selected ->
                    prefs.uiMode =
                        when (selected) {
                            "开" -> blbl.cat3399.core.prefs.AppPrefs.UI_MODE_TV
                            "关" -> blbl.cat3399.core.prefs.AppPrefs.UI_MODE_NORMAL
                            else -> blbl.cat3399.core.prefs.AppPrefs.UI_MODE_AUTO
                        }
                    Toast.makeText(this, "TV 模式：$selected", Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }

            "每行卡片数量" -> {
                val options = listOf("自动", "1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "每行卡片数量",
                    items = options,
                    current = gridSpanText(prefs.gridSpanCount),
                ) { selected ->
                    prefs.gridSpanCount = if (selected == "自动") 0 else (selected.toIntOrNull() ?: 0)
                    Toast.makeText(this, "每行卡片：${gridSpanText(prefs.gridSpanCount)}", Toast.LENGTH_SHORT).show()
                    refreshSection(entry.title)
                }
            }

            "动态页每行卡片数量" -> {
                val options = listOf("1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "动态页每行卡片数量",
                    items = options,
                    current = gridSpanText(prefs.dynamicGridSpanCount),
                ) { selected ->
                    prefs.dynamicGridSpanCount = (selected.toIntOrNull() ?: 3).coerceIn(1, 6)
                    Toast.makeText(this, "动态每行：${gridSpanText(prefs.dynamicGridSpanCount)}", Toast.LENGTH_SHORT).show()
                    refreshSection(entry.title)
                }
            }

            "弹幕开关" -> {
                prefs.danmakuEnabled = !prefs.danmakuEnabled
                Toast.makeText(this, "弹幕：${if (prefs.danmakuEnabled) "开" else "关"}", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "默认开启字幕" -> {
                prefs.subtitleEnabledDefault = !prefs.subtitleEnabledDefault
                Toast.makeText(this, "默认字幕：${if (prefs.subtitleEnabledDefault) "开" else "关"}", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "弹幕透明度" -> {
                val options = listOf(1.0f, 0.8f, 0.6f, 0.4f, 0.2f)
                showChoiceDialog(
                    title = "弹幕透明度",
                    items = options.map { String.format(Locale.US, "%.2f", it) },
                    current = String.format(Locale.US, "%.2f", prefs.danmakuOpacity),
                ) { selected ->
                    prefs.danmakuOpacity = selected.toFloatOrNull()?.coerceIn(0.05f, 1.0f) ?: prefs.danmakuOpacity
                    refreshSection(entry.title)
                }
            }

            "弹幕字体大小" -> {
                val options = listOf(14, 16, 18, 20, 24, 28, 32, 36, 40)
                showChoiceDialog(
                    title = "弹幕字体大小(sp)",
                    items = options.map { it.toString() },
                    current = prefs.danmakuTextSizeSp.toInt().toString(),
                ) { selected ->
                    prefs.danmakuTextSizeSp = (selected.toIntOrNull() ?: 18).toFloat().coerceIn(10f, 60f)
                    refreshSection(entry.title)
                }
            }

            "弹幕占屏比" -> {
                val options = listOf(
                    0.25f to "1/4",
                    0.50f to "1/2",
                    0.75f to "3/4",
                    1.00f to "不限",
                )
                showChoiceDialog(
                    title = "弹幕占屏比",
                    items = options.map { it.second },
                    current = areaText(prefs.danmakuArea),
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: 1.0f
                    prefs.danmakuArea = value
                    refreshSection(entry.title)
                }
            }

            "弹幕速度" -> {
                val options = (1..10).map { it.toString() }
                showChoiceDialog(
                    title = "弹幕速度(1~10)",
                    items = options,
                    current = prefs.danmakuSpeed.toString(),
                ) { selected ->
                    prefs.danmakuSpeed = (selected.toIntOrNull() ?: 4).coerceIn(1, 10)
                    refreshSection(entry.title)
                }
            }

            "跟随B站弹幕屏蔽" -> {
                prefs.danmakuFollowBiliShield = !prefs.danmakuFollowBiliShield
                refreshSection(entry.title)
            }

            "智能云屏蔽" -> {
                prefs.danmakuAiShieldEnabled = !prefs.danmakuAiShieldEnabled
                refreshSection(entry.title)
            }

            "智能云屏蔽等级" -> {
                val options = listOf("默认(3)") + (1..10).map { it.toString() }
                showChoiceDialog(
                    title = "智能云屏蔽等级",
                    items = options,
                    current = aiLevelText(prefs.danmakuAiShieldLevel),
                ) { selected ->
                    prefs.danmakuAiShieldLevel = if (selected.startsWith("默认")) 0 else (selected.toIntOrNull() ?: 0)
                    refreshSection(entry.title)
                }
            }

            "允许滚动弹幕" -> {
                prefs.danmakuAllowScroll = !prefs.danmakuAllowScroll
                refreshSection(entry.title)
            }

            "允许顶部悬停弹幕" -> {
                prefs.danmakuAllowTop = !prefs.danmakuAllowTop
                refreshSection(entry.title)
            }

            "允许底部悬停弹幕" -> {
                prefs.danmakuAllowBottom = !prefs.danmakuAllowBottom
                refreshSection(entry.title)
            }

            "允许彩色弹幕" -> {
                prefs.danmakuAllowColor = !prefs.danmakuAllowColor
                refreshSection(entry.title)
            }

            "允许特殊弹幕" -> {
                prefs.danmakuAllowSpecial = !prefs.danmakuAllowSpecial
                refreshSection(entry.title)
            }

            "默认画质" -> {
                val options =
                    listOf(16, 32, 64, 74, 80, 112, 116, 120, 127).map { it to qnText(it) }
                showChoiceDialog(
                    title = "默认画质(qn)",
                    items = options.map { it.second },
                    current = qnText(prefs.playerPreferredQn),
                ) { selected ->
                    val qn = options.firstOrNull { it.second == selected }?.first
                    if (qn != null) prefs.playerPreferredQn = qn
                    refreshSection(entry.title)
                }
            }

            "默认音轨" -> {
                val options = listOf(30280, 30232, 30216)
                showChoiceDialog(
                    title = "默认音轨",
                    items = options.map { audioText(it) },
                    current = audioText(prefs.playerPreferredAudioId),
                ) { selected ->
                    val id = selected.substringBefore(" ").toIntOrNull()
                    if (id != null) prefs.playerPreferredAudioId = id
                    refreshSection(entry.title)
                }
            }

            "默认播放速度" -> {
                val options = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                showChoiceDialog(
                    title = "默认播放速度",
                    items = options.map { String.format(Locale.US, "%.2fx", it) },
                    current = String.format(Locale.US, "%.2fx", prefs.playerSpeed),
                ) { selected ->
                    val v = selected.removeSuffix("x").toFloatOrNull()
                    if (v != null) prefs.playerSpeed = v.coerceIn(0.25f, 3.0f)
                    refreshSection(entry.title)
                }
            }

            "字幕语言" -> {
                val options = listOf(
                    "auto" to "自动",
                    "zh-Hans" to "中文(简体)",
                    "zh-Hant" to "中文(繁体)",
                    "en" to "English",
                    "ja" to "日本語",
                    "ko" to "한국어",
                )
                showChoiceDialog(
                    title = "字幕语言",
                    items = options.map { it.second },
                    current = subtitleLangText(prefs.subtitlePreferredLang),
                ) { selected ->
                    val code = options.firstOrNull { it.second == selected }?.first ?: "auto"
                    prefs.subtitlePreferredLang = code
                    refreshSection(entry.title)
                }
            }

            "视频编码" -> {
                val options = listOf("AVC", "HEVC", "AV1")
                showChoiceDialog(
                    title = "视频编码(偏好)",
                    items = options,
                    current = prefs.playerPreferredCodec,
                ) { selected ->
                    prefs.playerPreferredCodec = selected
                    refreshSection(entry.title)
                }
            }

            "显示视频调试信息" -> {
                prefs.playerDebugEnabled = !prefs.playerDebugEnabled
                refreshSection(entry.title)
            }

            "播放结束双击返回" -> {
                prefs.playerDoubleBackOnEnded = !prefs.playerDoubleBackOnEnded
                refreshSection(entry.title)
            }

            else -> AppLog.i("Settings", "click ${entry.title}")
        }
    }

    private fun showChoiceDialog(title: String, items: List<String>, current: String, onPicked: (String) -> Unit) {
        val checked = items.indexOf(current).coerceAtLeast(-1)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                if (which in items.indices) onPicked(items[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showUserAgentDialog(sectionIndex: Int, focusTitle: String) {
        val prefs = BiliClient.prefs
        val input = EditText(this).apply {
            setText(prefs.userAgent)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_NORMAL
            minLines = 3
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("User-Agent")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val ua = input.text?.toString().orEmpty().trim()
                if (ua.isBlank()) {
                    Toast.makeText(this, "User-Agent 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.userAgent = ua
                Toast.makeText(this, "已更新 User-Agent", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNeutralButton("重置默认") { _, _ ->
                prefs.userAgent = blbl.cat3399.core.prefs.AppPrefs.DEFAULT_UA
                Toast.makeText(this, "已重置 User-Agent", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearLoginDialog(sectionIndex: Int, focusTitle: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除登录")
            .setMessage("将清除 Cookie（SESSDATA 等），需要重新登录。确定继续吗？")
            .setPositiveButton("确定清除") { _, _ ->
                BiliClient.cookies.clearAll()
                BiliClient.prefs.webRefreshToken = null
                BiliClient.prefs.webCookieRefreshCheckedEpochDay = -1L
                BiliClient.prefs.biliTicketCheckedEpochDay = -1L
                Toast.makeText(this, "已清除 Cookie", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun audioText(id: Int): String = when (id) {
        30280 -> "30280 192K"
        30232 -> "30232 132K"
        30216 -> "30216 64K"
        else -> "$id"
    }

    private fun subtitleLangText(code: String): String = when (code) {
        "auto" -> "自动"
        "zh-Hans" -> "中文(简体)"
        "zh-Hant" -> "中文(繁体)"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        else -> code
    }

    private fun areaText(area: Float): String = when {
        area >= 0.99f -> "不限"
        area >= 0.74f -> "3/4"
        area >= 0.49f -> "1/2"
        else -> "1/4"
    }

    private fun aiLevelText(level: Int): String = if (level == 0) "默认(3)" else level.coerceIn(1, 10).toString()

    private fun gridSpanText(span: Int): String = if (span <= 0) "自动" else span.toString()

    private fun tvModeText(mode: String): String = when (mode) {
        blbl.cat3399.core.prefs.AppPrefs.UI_MODE_TV -> "开"
        blbl.cat3399.core.prefs.AppPrefs.UI_MODE_NORMAL -> "关"
        else -> "自动"
    }

    private fun qnText(qn: Int): String = when (qn) {
        16 -> "360P 流畅(16)"
        32 -> "480P 清晰(32)"
        64 -> "720P 高清(64)"
        74 -> "720P60 高帧率(74)"
        80 -> "1080P 高清(80)"
        112 -> "1080P+ 高码率(112)"
        116 -> "1080P60 高帧率(116)"
        120 -> "4K 超清(120)"
        127 -> "8K 超高清(127)"
        125 -> "HDR 真彩色(125)"
        126 -> "杜比视界(126)"
        129 -> "HDR Vivid(129)"
        6 -> "240P 极速(6)"
        100 -> "智能修复(100)"
        else -> "qn $qn"
    }
}
