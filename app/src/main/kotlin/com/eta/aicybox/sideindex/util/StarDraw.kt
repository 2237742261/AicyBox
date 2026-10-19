package com.eta.aicybox.sideindex.util

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * ★ 收藏图标自绘。
 *
 * 不依赖系统字体是否带 U+2605 字形：部分 ROM 的字体回退链里没有这个字符，
 * drawText 会画成空白，字母条上那一段看起来就像"收藏栏没出现"。
 */
object StarDraw {

    const val STAR = "★"

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun isStar(label: String): Boolean = label == STAR

    /** 画一个外接圆半径为 r 的五角星，圆心 (cx, cy) */
    fun draw(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        path.reset()
        val inner = r * 0.44f
        for (i in 0 until 10) {
            val angle = Math.toRadians((-90 + i * 36).toDouble())
            val radius = if (i % 2 == 0) r else inner
            val px = cx + (radius * Math.cos(angle)).toFloat()
            val py = cy + (radius * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }
}
