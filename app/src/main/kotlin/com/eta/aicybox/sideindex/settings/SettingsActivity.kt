package com.eta.aicybox.sideindex.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.eta.aicybox.R
import com.eta.aicybox.sideindex.config.ConfigCodec
import com.eta.aicybox.sideindex.config.ModuleConfig
import com.eta.aicybox.sideindex.config.PrefKeys
import com.eta.aicybox.sideindex.config.Side
import com.eta.aicybox.sideindex.ui.TriggerBandView
import com.eta.aicybox.sideindex.util.Haptics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {

    private lateinit var container: LinearLayout
    private var scroll: ScrollView? = null
    private var currentCard: LinearLayout? = null
    private var config: ModuleConfig = ModuleConfig()

    private var previewConfig: ModuleConfig? = null
    private var bandPreview: TriggerBandView? = null
    private var lastVibrationPreviewAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        container = findViewById(R.id.container)
        // ColorOS 上 targetSdk 36 强制全面屏，标题栏会浮在内容之上；这里自己吃掉系统栏高度，
        // 否则最上面的设置项永远被压在状态栏/标题栏底下，怎么滑都出不来。
        scroll = findViewById<ScrollView>(R.id.settings_scroll).apply {
            clipToPadding = false
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            requestApplyInsets()
        }
        PrefStore.ensureInitialized(this)
        // 进设置页只"拉"生效配置到私有镜像，绝不回写（1.31.5 修：以前这里是 write { }，
        // 会把私有镜像里的旧值推回 Secure —— 表现就是"打开设置页把生效配置改掉了"）
        PrefStore.syncFromChannel(this)
        buildUi()
    }

    override fun onResume() { super.onResume(); buildUi() }

    override fun onPause() {
        super.onPause()
        bandPreview?.alpha = 0f
    }


    // ---------------- 界面 ----------------

    private fun buildUi() {
        val keepScroll = scroll?.scrollY ?: 0
        config = PrefStore.currentConfig(this)
        previewConfig = null
        container.removeAllViews()
        scroll?.setBackgroundColor(if (isNight()) 0xFF000000.toInt() else 0xFFF5F6F8.toInt())
        window?.setBackgroundDrawable(ColorDrawable(if (isNight()) 0xFF000000.toInt() else 0xFFF5F6F8.toInt()))
        currentCard = null

        header()
        section("总开关")
        switchRow("启用侧边索引", "在桌面侧缘滑动呼出字母索引（总开关）", config.enabled) { v ->
            PrefStore.write(this) { it.putBoolean(PrefKeys.ENABLED, v) }
            buildUi()
        }
        choiceRow(
            "呼出方式",
            listOf("右侧（默认）", "左侧", "两侧均可"),
            when (config.triggerSide) {
                "left" -> 1
                "both" -> 2
                else -> 0
            }
        ) { pick ->
            PrefStore.write(this) {
                it.putString(
                    PrefKeys.TRIGGER_SIDE,
                    when (pick) {
                        1 -> "left"
                        2 -> "both"
                        else -> "right"
                    }
                )
                // 老键同步写，兼容还按 both_sides 读的旧版本
                it.putBoolean(PrefKeys.BOTH_SIDES, pick == 2)
            }
            buildUi()
        }

        buildEdgeGesture()
        buildIndexDisplay()
        buildLaunchAndFeedback()
        buildDiagnostics()
        // 重建后回到原来的位置，否则某个开关一拨就跳回顶部
        scroll?.post { scroll?.scrollTo(0, keepScroll) }
    }

    /** 页面标题（没有 ActionBar 了，自己画一个） */
    private fun header() {
        container.addView(tv("侧滑索引", 24f, textPrimary()).apply {
            setPadding(dp(24), dp(14), dp(24), dp(2))
            typeface = Typeface.DEFAULT_BOLD
        })
        container.addView(tv("边缘滑动呼出字母索引", 12f, textSecondary()).apply {
            setPadding(dp(24), 0, dp(24), dp(4))
        })
    }

    /** 边缘手势：调参时实时预览（高亮区域即实际触发范围） */
    private fun buildEdgeGesture() {
        section("边缘手势")
        hintRow("高亮区域即实际触发范围，可实时预览")

        sliderRow(
            "触发宽度", " dp", config.triggerWidthDp, 0, 35,
            live = { v, dragging -> liveBandPreview({ it.copy(triggerWidthDp = v) }, dragging) }
        ) { v -> PrefStore.write(this) { it.putInt(PrefKeys.TRIGGER_WIDTH_DP, v) } }

        sliderRow(
            "触发高度", " % 屏高", config.barHeightPercent, 20, 100,
            live = { v, dragging -> liveBandPreview({ it.copy(barHeightPercent = v) }, dragging) }
        ) { v -> PrefStore.write(this) { it.putInt(PrefKeys.BAR_HEIGHT_PERCENT, v) } }

        sliderRow(
            "垂直位置", " % 屏高", config.triggerPositionPercent, 0, 100,
            live = { v, dragging -> liveBandPreview({ it.copy(triggerPositionPercent = v) }, dragging) }
        ) { v -> PrefStore.write(this) { it.putInt(PrefKeys.TRIGGER_POSITION_PERCENT, v) } }

        sliderRow(
            "震动强度", " %", config.vibrateStrength, 0, 100,
            live = { v, dragging -> previewVibration(v, dragging) }
        ) { v -> PrefStore.write(this) { it.putInt(PrefKeys.VIBRATE_STRENGTH, v) } }

    }

    /** 索引显示 */
    private fun buildIndexDisplay() {
        section("索引显示")
        switchRow(
            "沉浸模式",
            "开: 使用壁纸模糊背景，关: 直接使用渲染模糊",
            config.immersive
        ) { v ->
            // 与全局索引互斥：开沉浸自动关全局
            PrefStore.write(this) {
                it.putBoolean(PrefKeys.IMMERSIVE, v)
                if (v) it.putBoolean(PrefKeys.ONLY_LAUNCHER, true)
            }
            buildUi()
        }
        switchRow(
            "全局索引",
            "在任何应用里都能呼出（关掉就只在桌面呼出；下面两项只在开启时生效）",
            !config.onlyLauncher
        ) { v ->
            // 与沉浸模式互斥：开全局自动关沉浸
            PrefStore.write(this) {
                it.putBoolean(PrefKeys.ONLY_LAUNCHER, !v)
                if (v) it.putBoolean(PrefKeys.IMMERSIVE, false)
            }
            buildUi()
        }
        switchRow(
            "游戏中屏蔽手势",
            "游戏里不呼出（需开启「全局呼出索引」，桌面不受影响）",
            config.gameBlock
        ) { v ->
            PrefStore.write(this) { it.putBoolean(PrefKeys.GAME_BLOCK, v) }
            buildUi()
        }
        switchRow(
            "横屏时暂停",
            "横屏时不呼出（竖屏照常）",
            config.landscapePause
        ) { v ->
            PrefStore.write(this) { it.putBoolean(PrefKeys.LANDSCAPE_PAUSE, v) }
            buildUi()
        }
        actionRow(
            "应用黑名单",
            if (config.blacklist.isBlank()) "还没添加（需开启「全局呼出索引」，这些应用里不响应呼出）"
            else "已添加 ${config.blacklist.split(',').filter { it.isNotBlank() }.size} 个，点按管理"
        ) {
            startActivity(
                Intent(this, AppPickerActivity::class.java)
                    .putExtra(AppPickerActivity.EXTRA_TARGET, PrefKeys.BLACKLIST)
            )
        }
        choiceRow(
            "每行图标数",
            listOf("4 个", "5 个"),
            if (config.gridColumns <= 4) 0 else 1
        ) { pick ->
            PrefStore.write(this) { it.putInt(PrefKeys.GRID_COLUMNS, if (pick == 0) 4 else 5) }
            buildUi()
        }
    }

    private fun buildLaunchAndFeedback() {
        section("打开方式")
        switchRow(
            "用小窗打开应用",
            "失败时自动退回全屏",
            config.freeformLaunch
        ) { v ->
            PrefStore.write(this) { it.putBoolean(PrefKeys.FREEFORM_LAUNCH, v) }
            buildUi()
        }
    }

    private fun buildDiagnostics() {
        section("诊断与维护")
        switchRow(
            "详细日志",
            "记录手势细节，排查问题用",
            config.logVerbose
        ) { v ->
            PrefStore.write(this) { it.putBoolean(PrefKeys.LOG_VERBOSE, v) }
            buildUi()
        }
        actionRow(
            "重启系统界面",
            "点一下立即生效，不用重启手机"
        ) { restartSystemUi() }
        actionRow("恢复默认设置", null) { confirmReset() }
    }

    // ---------------- 实时预览 ----------------

    private fun liveBandPreview(update: (ModuleConfig) -> ModuleConfig, dragging: Boolean) {
        val updated = update(previewConfig ?: config)
        previewConfig = updated
        val decor = window?.decorView as? ViewGroup ?: return
        val view = bandPreview ?: TriggerBandView(this).apply {
            alpha = 0f
            decor.addView(this, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            bandPreview = this
        }
        view.updateConfig(updated, Side.RIGHT, updated.onLeft() && updated.onRight())
        if (dragging) view.setLive(true) else view.hideLater(900)
    }

    private fun previewVibration(value: Int, dragging: Boolean) {
        if (!dragging) return
        if (value <= 0) return // 强度 0 = 不震动
        val now = System.currentTimeMillis()
        if (now - lastVibrationPreviewAt < 120) return
        lastVibrationPreviewAt = now
        Haptics.tick(this, value, null)
    }

    /** 通过 root 重启系统界面，让改动立即生效 */
    private fun restartSystemUi() {
        val done = try {
            val code = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "kill -9 \$(pidof com.android.systemui)"))
                .waitFor()
            code == 0
        } catch (t: Throwable) {
            false
        }
        toast(if (done) "已请求重启系统界面，稍等几秒即可生效" else "重启失败（需要 root 授权）")
    }

    // ---------------- 行控件 ----------------

    private var rowIndexInCard = 0

    private fun section(title: String) {
        rowIndexInCard = 0
        val header = tv(title, 14f, textSecondary()).apply { setPadding(dp(32), dp(26), dp(32), dp(10)) }
        (container as ViewGroup).addView(header)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (isNight()) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt())
            }
            elevation = 0f
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(12), 0, dp(12), 0) }
        (container as ViewGroup).addView(card, lp)
        currentCard = card
    }

    private fun addRow(view: View) {
        val card = currentCard ?: run { (container as ViewGroup).addView(view); return }
        if (rowIndexInCard > 0) {
            val divider = View(this).apply { setBackgroundColor(if (isNight()) 0x14FFFFFF else 0x0F000000) }
            card.addView(divider, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { leftMargin = dp(18); rightMargin = dp(18) })
        }
        card.addView(view)
        rowIndexInCard++
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun hintRow(text: String) {
        addRow(tv(text, 12f, textSecondary()).apply { setPadding(dp(20), dp(8), dp(20), dp(12)) })
    }

    private fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(title, 16f, textPrimary()))
        if (!subtitle.isNullOrEmpty()) {
            texts.addView(tv(subtitle, 11f, textSecondary()).apply { setPadding(0, dp(3), 0, 0) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        })
        addRow(row)
    }

    private fun sliderRow(
        title: String,
        unit: String,
        value: Int,
        min: Int,
        max: Int,
        live: ((Int, Boolean) -> Unit)? = null,
        onChange: (Int) -> Unit
    ) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            tv(title, 16f, textPrimary()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val valueLabel = tv("$value$unit", 13f, textSecondary())
        head.addView(valueLabel)
        wrap.addView(head)
        wrap.addView(SeekBar(this).apply {
            this.max = (max - min).coerceAtLeast(1)
            progress = (value - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val current = progress + min
                    valueLabel.text = "$current$unit"
                    if (fromUser) live?.invoke(current, true)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    live?.invoke((seekBar?.progress ?: 0) + min, true)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val current = (seekBar?.progress ?: 0) + min
                    onChange(current)
                    live?.invoke(current, false)
                }
            })
        })
        addRow(wrap)
    }

    private fun choiceRow(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        row.addView(
            tv(title, 16f, textPrimary()),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(tv(options.getOrElse(selected) { "-" }.substringBefore("（"), 13f, accent()))
        row.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                    dialog.dismiss(); onPick(which)
                }
                .setNegativeButton("取消", null)
                .show()
        }
        addRow(row)
    }

    private fun actionRow(title: String, subtitle: String?, onClick: (() -> Unit)?) {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        wrap.addView(tv(title, 16f, if (onClick != null) accent() else textPrimary()))
        if (!subtitle.isNullOrEmpty()) {
            wrap.addView(tv(subtitle, 11f, textSecondary()).apply { setPadding(0, dp(3), 0, 0) })
        }
        if (onClick != null) wrap.setOnClickListener { onClick() }
        addRow(wrap)
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("恢复默认设置")
            .setMessage("将把全部设置恢复为默认值。")
            .setPositiveButton("恢复") { _, _ ->
                PrefStore.prefs(this).edit().clear().commit()
                // ★ 收藏计数存在 Secure（eta_sideindex_star）：恢复默认时一起清空
                try {
                    android.provider.Settings.Secure.putString(contentResolver, "eta_sideindex_star", null)
                } catch (t: Throwable) {
                }
                // 默认值必须显式写回 SP 与 Secure：只清 SP 的话，write() 会把 Secure 里的旧配置又铺回来（曾导致"恢复默认"完全无效）
                PrefStore.applyConfig(this, ModuleConfig())
                buildUi()
                toast("已恢复默认设置")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun tv(text: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun textPrimary(): Int = themed(android.R.attr.textColorPrimary, Color.DKGRAY)
    private fun textSecondary(): Int = themed(android.R.attr.textColorSecondary, Color.GRAY)
    private fun accent(): Int = themed(android.R.attr.colorAccent, 0xFF3482FF.toInt())

    private fun themed(attr: Int, fallback: Int): Int = try {
        val value = TypedValue()
        if (theme.resolveAttribute(attr, value, true)) {
            if (value.resourceId != 0) resources.getColor(value.resourceId, theme) else value.data
        } else fallback
    } catch (t: Throwable) {
        fallback
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

}
