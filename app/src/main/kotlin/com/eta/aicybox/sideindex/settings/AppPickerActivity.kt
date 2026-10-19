package com.eta.aicybox.sideindex.settings

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.eta.aicybox.sideindex.Const
import com.eta.aicybox.R
import com.eta.aicybox.sideindex.config.PrefKeys
import com.eta.aicybox.sideindex.util.XLog
import java.text.Collator

data class AppEntry(val packageName: String, val label: String, val hasLauncherIcon: Boolean)

/** 应用范围名单选择页（白名单/黑名单共用同一份名单） */
class AppPickerActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var filterEdit: EditText
    private lateinit var showAllSwitch: Switch

    private val all = ArrayList<AppEntry>()
    private val shown = ArrayList<AppEntry>()
    private val iconCache = HashMap<String, Drawable?>()
    private var adapter: AppAdapter? = null
    private var targetKey: String = PrefKeys.LANDSCAPE_WHITELIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        targetKey = intent?.getStringExtra(EXTRA_TARGET) ?: PrefKeys.LANDSCAPE_WHITELIST
        val pageTitle = if (targetKey == PrefKeys.LANDSCAPE_WHITELIST) {
            "横屏白名单：这些横屏应用里也能呼出索引"
        } else {
            "黑名单：这些应用里无论横竖屏都不响应"
        }
        // 没有 ActionBar 了，标题画在页面里，并自己吃掉状态栏/导航栏高度
        findViewById<TextView>(R.id.page_title).text = pageTitle
        findViewById<View>(R.id.picker_root).apply {
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            requestApplyInsets()
        }

        listView = findViewById(R.id.app_list)
        filterEdit = findViewById(R.id.filter)
        showAllSwitch = findViewById(R.id.show_all)
        filterEdit.hint = "搜索应用名或包名"

        val appAdapter = AppAdapter()
        adapter = appAdapter
        listView.adapter = appAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = shown.getOrNull(position) ?: return@setOnItemClickListener
            PrefStore.toggleIn(this, targetKey, entry.packageName)
            appAdapter.notifyDataSetChanged()
        }

        showAllSwitch.setOnCheckedChangeListener { _, _ -> applyFilter(filterEdit.text?.toString().orEmpty()) }
        filterEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
        })

        loadApplications()
    }

    private fun loadApplications() {
        Thread {
            val pm = packageManager
            val launcherPackages = HashSet<String>()
            try {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                for (info in pm.queryIntentActivities(intent, 0)) {
                    info.activityInfo?.packageName?.let { launcherPackages.add(it) }
                }
            } catch (t: Throwable) {
                XLog.w("查询桌面应用失败", t)
            }

            val result = ArrayList<AppEntry>()
            try {
                for (appInfo in pm.getInstalledApplications(0)) {
                    val pkg = appInfo.packageName ?: continue
                    if (pkg == Const.MODULE_PACKAGE) continue
                    val label = try { appInfo.loadLabel(pm).toString() } catch (t: Throwable) { pkg }
                    result.add(AppEntry(pkg, label, launcherPackages.contains(pkg)))
                }
                val collator = Collator.getInstance()
                result.sortWith(compareBy(collator) { it.label })
            } catch (t: Throwable) {
                XLog.w("读取已安装应用列表失败", t)
            }

            runOnUiThread {
                all.clear()
                all.addAll(result)
                applyFilter(filterEdit.text?.toString().orEmpty())
                if (all.isEmpty()) toast("未读取到应用列表")
            }
        }.start()
    }

    private fun applyFilter(query: String) {
        val keyword = query.trim().lowercase()
        val showAll = showAllSwitch.isChecked
        shown.clear()
        for (entry in all) {
            if (!showAll && !entry.hasLauncherIcon) continue
            if (keyword.isNotEmpty() &&
                !entry.label.lowercase().contains(keyword) &&
                !entry.packageName.lowercase().contains(keyword)
            ) continue
            shown.add(entry)
        }
        adapter?.notifyDataSetChanged()
    }

    private fun iconOf(pkg: String): Drawable? = iconCache.getOrPut(pkg) {
        try { packageManager.getApplicationIcon(pkg) } catch (t: Throwable) { null }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = shown[position].packageName.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val entry = shown[position]
            view.findViewById<TextView>(R.id.title).text = entry.label
            view.findViewById<TextView>(R.id.subtitle).text = entry.packageName
            view.findViewById<ImageView>(R.id.icon).setImageDrawable(iconOf(entry.packageName))
            view.findViewById<CheckBox>(R.id.check).isChecked =
                PrefStore.containsIn(this@AppPickerActivity, targetKey, entry.packageName)
            return view
        }
    }

    companion object {
        const val EXTRA_TARGET = "target"
    }
}
