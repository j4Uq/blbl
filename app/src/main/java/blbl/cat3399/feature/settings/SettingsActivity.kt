package blbl.cat3399.feature.settings

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val state = SettingsState()
    private lateinit var leftAdapter: SettingsLeftAdapter
    private lateinit var rightAdapter: SettingsEntryAdapter
    private lateinit var renderer: SettingsRenderer
    private lateinit var interactionHandler: SettingsInteractionHandler

    private val gaiaVgateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (!this::interactionHandler.isInitialized) return@registerForActivityResult
            interactionHandler.onGaiaVgateResult(result)
        }

    private val exportLogsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (!this::interactionHandler.isInitialized) return@registerForActivityResult
            interactionHandler.onExportLogsSelected(uri)
        }

    private val sections =
        listOf(
            "通用设置",
            "页面设置",
            "播放设置",
            "弹幕设置",
            "关于应用",
            "设备信息",
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        interactionHandler =
            SettingsInteractionHandler(
                activity = this,
                state = state,
                gaiaVgateLauncher = gaiaVgateLauncher,
                exportLogsLauncher = exportLogsLauncher,
            )

        binding.btnBack.setOnClickListener { finish() }

        leftAdapter = SettingsLeftAdapter { index -> renderer.showSection(index, keepScroll = false) }
        binding.recyclerLeft.layoutManager = LinearLayoutManager(this)
        binding.recyclerLeft.itemAnimator = null
        binding.recyclerLeft.adapter = leftAdapter
        leftAdapter.submit(sections, selected = 0)

        binding.recyclerRight.layoutManager = LinearLayoutManager(this)
        binding.recyclerRight.itemAnimator = null
        rightAdapter = SettingsEntryAdapter { entry -> interactionHandler.onEntryClicked(entry) }
        binding.recyclerRight.adapter = rightAdapter

        renderer =
            SettingsRenderer(
                activity = this,
                binding = binding,
                state = state,
                sections = sections,
                leftAdapter = leftAdapter,
                rightAdapter = rightAdapter,
                onSectionShown = { sectionName -> interactionHandler.onSectionShown(sectionName) },
            )
        interactionHandler.renderer = renderer

        renderer.installFocusListener()
        renderer.showSection(0)
    }

    override fun onResume() {
        super.onResume()
        renderer.ensureInitialFocus()
    }

    override fun onDestroy() {
        renderer.uninstallFocusListener()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        if (hasFocus) renderer.restorePendingFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && renderer.isNavKey(event.keyCode)) {
            renderer.ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
