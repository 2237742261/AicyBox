package com.eta.aicybox.sideindex.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper

/**
 * 呼出索引时的背景：桌面壁纸 + 真正的柔和模糊。
 *
 * 之前那版是「缩到 1/16 再拉回来」，得到的是**马赛克块**（像低分辨率图，不像模糊）。
 * 现在改成：壁纸画进 1/[SRC_DIVISOR] 的小位图 → 在小图上跑 [BLUR_PASSES] 次可分离盒式模糊
 * （水平 + 垂直各一遍算一次）→ 直接铺满整屏。观感就是锁屏那种柔和模糊，而且
 * 只在预热时算一次、结果缓存，绘制阶段零额外开销。
 *
 * 取不到壁纸（动态壁纸 / 接口拒绝）时返回 null，浮层退回原来的纯遮罩，不影响使用。
 */
object BlurredWallpaper {

    /** 源图降采样倍数：模糊本身会抹掉细节，1/4 足够清楚也够省 */
    private const val SRC_DIVISOR = 4

    /** 盒式模糊半径（在 1/[SRC_DIVISOR] 尺度上，等效到屏幕要 ×[SRC_DIVISOR]） */
    private const val BLUR_RADIUS = 7

    /** 盒式模糊次数：3 次已经很接近高斯，再多看不出差别 */
    private const val BLUR_PASSES = 3

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var bitmap: Bitmap? = null
    @Volatile private var loadedId = Int.MIN_VALUE
    @Volatile private var loading = false
    @Volatile private var listener: ((Bitmap?) -> Unit)? = null

    /** 生成完成后回主线程通知浮层换图 */
    fun setListener(l: ((Bitmap?) -> Unit)?) { listener = l }

    fun current(): Bitmap? = bitmap

    /**
     * 心跳里预热、呼出时兜底调用。壁纸没变且已经生成过就直接返回，
     * 所以放在心跳里不会反复解码壁纸。
     */
    fun ensure(ctx: Context, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (loading) return
        val appCtx = try { ctx.applicationContext ?: ctx } catch (t: Throwable) { ctx }
        val id = wallpaperId(appCtx)
        if (bitmap != null && (id == loadedId || id == Int.MIN_VALUE)) return
        loading = true
        Thread {
            val bmp = render(appCtx, width, height)
            main.post {
                loading = false
                if (bmp != null) {
                    bitmap = bmp
                    loadedId = id
                }
                try {
                    listener?.invoke(bmp)
                } catch (t: Throwable) {
                    XLog.d("模糊背景回调失败：${t.javaClass.simpleName}")
                }
            }
        }.start()
    }

    private fun wallpaperId(ctx: Context): Int = try {
        WallpaperManager.getInstance(ctx).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    } catch (t: Throwable) {
        Int.MIN_VALUE
    }

    private fun render(ctx: Context, width: Int, height: Int): Bitmap? = try {
        val d = WallpaperManager.getInstance(ctx).drawable
        if (d == null) {
            XLog.w("模糊背景：拿不到桌面壁纸，退回纯遮罩")
            null
        } else {
            val sw = (width / SRC_DIVISOR).coerceAtLeast(1)
            val sh = (height / SRC_DIVISOR).coerceAtLeast(1)
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(small)
            d.setBounds(0, 0, sw, sh)
            d.draw(canvas)
            boxBlur(small, BLUR_RADIUS, BLUR_PASSES)
            XLog.i("模糊背景就绪：${small.width}×${small.height}（半径 $BLUR_RADIUS × $BLUR_PASSES 次）")
            small
        }
    } catch (t: Throwable) {
        XLog.w("模糊背景生成失败：${t.javaClass.simpleName}")
        null
    }

    /**
     * 可分离盒式模糊（水平一遍 + 垂直一遍 = 一次），迭代 [passes] 次。
     * 用滑动窗口累加，复杂度与半径无关；只在 1/[SRC_DIVISOR] 的小图上跑，成本可忽略。
     */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int) {
        if (radius < 1 || passes < 1) return
        val w = src.width
        val h = src.height
        if (w < 2 || h < 2) return
        val size = w * h
        val pixels = IntArray(size)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(size)
        val window = radius * 2 + 1

        repeat(passes) {
            // ---- 水平 ----
            for (y in 0 until h) {
                val row = y * w
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (i in -radius..radius) {
                    val p = pixels[row + i.coerceIn(0, w - 1)]
                    a += (p ushr 24) and 0xFF
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                }
                for (x in 0 until w) {
                    tmp[row + x] = ((a / window) shl 24) or ((r / window) shl 16) or
                        ((g / window) shl 8) or (b / window)
                    val out = pixels[row + (x - radius).coerceIn(0, w - 1)]
                    val inP = pixels[row + (x + radius + 1).coerceIn(0, w - 1)]
                    a += ((inP ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                    r += ((inP ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                    g += ((inP ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                    b += (inP and 0xFF) - (out and 0xFF)
                }
            }
            // ---- 垂直 ----
            for (x in 0 until w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (i in -radius..radius) {
                    val p = tmp[i.coerceIn(0, h - 1) * w + x]
                    a += (p ushr 24) and 0xFF
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                }
                for (y in 0 until h) {
                    pixels[y * w + x] = ((a / window) shl 24) or ((r / window) shl 16) or
                        ((g / window) shl 8) or (b / window)
                    val out = tmp[(y - radius).coerceIn(0, h - 1) * w + x]
                    val inP = tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                    a += ((inP ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                    r += ((inP ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                    g += ((inP ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                    b += (inP and 0xFF) - (out and 0xFF)
                }
            }
        }
        src.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}
