package com.eta.aicybox.sideindex.index

import android.icu.text.Transliterator
import com.eta.aicybox.sideindex.util.XLog

/** 拼音首字母（系统 ICU 音译器，无需内置码表；Transliterator 非线程安全，加锁使用） */
object Pinyin {

    private val hanLatin: Transliterator? by lazy {
        try {
            Transliterator.getInstance("Han-Latin; Latin-ASCII")
        } catch (t: Throwable) {
            XLog.w("ICU Han-Latin 不可用，汉字归入 # 分组", t)
            null
        }
    }

    @Synchronized
    fun initial(text: String?): String {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return "#"
        val first = s[0]
        if (first in 'A'..'Z' || first in 'a'..'z') return first.uppercaseChar().toString()
        if (isHan(first)) {
            val converted = try { hanLatin?.transliterate(first.toString()) } catch (t: Throwable) { null }
            val letter = converted?.firstOrNull { it in 'A'..'Z' || it in 'a'..'z' }
            return letter?.uppercaseChar()?.toString() ?: "#"
        }
        return "#"
    }

    fun isHan(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
}
