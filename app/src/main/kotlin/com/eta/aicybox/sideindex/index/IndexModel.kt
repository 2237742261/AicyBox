package com.eta.aicybox.sideindex.index

/** 一个字母分组：label 显示文本，start/end 是它在应用列表里的区间（闭区间） */
data class IndexGroup(val label: String, val start: Int, val end: Int)

/** 只保留真实存在的首字母，多段合并，保证字母条不出现重复/空字母 */
object Grouping {

    fun build(initials: List<String>): List<IndexGroup> {
        if (initials.isEmpty()) return emptyList()
        val starts = LinkedHashMap<String, Int>()
        val ends = HashMap<String, Int>()
        for (i in initials.indices) {
            val key = initials[i].ifEmpty { "#" }
            if (!starts.containsKey(key)) starts[key] = i
            ends[key] = i
        }
        return starts.entries.sortedBy { it.value }
            .map { IndexGroup(it.key, it.value, ends[it.key] ?: it.value) }
    }

    /** 设置页预览用的假分组 A–Z */
    fun previewLetters(): List<IndexGroup> =
        ('A'..'Z').mapIndexed { i, c -> IndexGroup(c.toString(), i, i) } + IndexGroup("#", 26, 26)
}
