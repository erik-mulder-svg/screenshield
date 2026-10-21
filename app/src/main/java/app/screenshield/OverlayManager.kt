package app.screenshield

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * One small overlay window per censor box ("display over other apps").
 *
 * The windows are deliberately TOUCHABLE: since Android 12 the system forces
 * click-through (FLAG_NOT_TOUCHABLE) overlays to max 80% opacity, which made
 * the boxes see-through. A touchable window stays 100% opaque; touches on the
 * box itself are swallowed, touches elsewhere go to the app below.
 *
 * Must only be used on the main thread.
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val HL_PAD = 24
    }

    /** What to draw for one box. [colors] is a cols x rows mosaic, or null for solid black. */
    class Visual(val rect: Rect, val colors: IntArray?, val cols: Int, val rows: Int)

    private val wm = context.getSystemService(WindowManager::class.java)
    private val views = HashMap<Int, BoxView>()
    private val highlightViews = HashMap<Int, HighlightView>()
    private val highlightRects = HashMap<Int, Rect>()

    /** Bright outlines around highlighted areas. These windows are click-through and transparent inside. */
    fun applyHighlights(areas: Map<Int, Rect>) {
        for (id in highlightViews.keys.filter { it !in areas }) removeHighlight(id)
        for ((id, r) in areas) {
            val padded = Rect(r).apply { inset(-HL_PAD, -HL_PAD) }
            val existing = highlightViews[id]
            if (existing == null) {
                val v = HighlightView(context)
                try {
                    wm.addView(v, highlightParams(padded))
                    highlightViews[id] = v
                    highlightRects[id] = padded
                } catch (_: Throwable) {
                }
            } else if (highlightRects[id] != padded) {
                try { wm.updateViewLayout(existing, highlightParams(padded)) } catch (_: Throwable) {}
                highlightRects[id] = padded
            }
        }
    }

    private fun removeHighlight(id: Int) {
        highlightViews.remove(id)?.let { try { wm.removeViewImmediate(it) } catch (_: Throwable) {} }
        highlightRects.remove(id)
    }

    private fun highlightParams(r: Rect): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            r.width().coerceAtLeast(1),
            r.height().coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = r.left
        lp.y = r.top
        lp.windowAnimations = 0
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        lp.fitInsetsSides = 0
        lp.title = "ScreenShield highlight"
        return lp
    }

    /** Thick neon outline with corner brackets; interior stays fully visible. */
    private class HighlightView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 9 * d; color = 0x66FF2D95
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = 0xFFFF2D95.toInt()
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6 * d; color = 0xFFFFE14D.toInt()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val i = 6 * d
            val l = i; val t = i; val r = width - i; val b = height - i
            val rad = 14 * d
            canvas.drawRoundRect(l, t, r, b, rad, rad, glow)
            canvas.drawRoundRect(l, t, r, b, rad, rad, line)
            val c = minOf(r - l, b - t) * 0.22f
            canvas.drawLine(l, t, l + c, t, corner); canvas.drawLine(l, t, l, t + c, corner)
            canvas.drawLine(r, t, r - c, t, corner); canvas.drawLine(r, t, r, t + c, corner)
            canvas.drawLine(l, b, l + c, b, corner); canvas.drawLine(l, b, l, b - c, corner)
            canvas.drawLine(r, b, r - c, b, corner); canvas.drawLine(r, b, r, b - c, corner)
        }
    }

    fun apply(boxes: Map<Int, Visual>) {
        for (id in views.keys.filter { it !in boxes }) remove(id)
        for ((id, v) in boxes) {
            val view = views[id]
            if (view == null) {
                val nv = BoxView(context)
                nv.setVisual(v)
                try {
                    wm.addView(nv, params(v.rect))
                    views[id] = nv
                } catch (_: Throwable) {
                }
            } else {
                if (view.rect != v.rect) {
                    try { wm.updateViewLayout(view, params(v.rect)) } catch (_: Throwable) {}
                }
                view.setVisual(v)
                view.visibility = View.VISIBLE
            }
        }
    }

    fun setHidden(ids: Set<Int>, hidden: Boolean) {
        for (id in ids) views[id]?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun clear() {
        for (id in views.keys.toList()) remove(id)
        for (id in highlightViews.keys.toList()) removeHighlight(id)
    }

    private fun remove(id: Int) {
        views.remove(id)?.let { try { wm.removeViewImmediate(it) } catch (_: Throwable) {} }
    }

    private fun params(r: Rect): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            r.width().coerceAtLeast(1),
            r.height().coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = r.left
        lp.y = r.top
        lp.alpha = 1f
        lp.windowAnimations = 0
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        lp.fitInsetsSides = 0
        lp.title = "ScreenShield box"
        return lp
    }

    private class BoxView(context: Context) : View(context) {
        var rect = Rect()
        private var colors: IntArray? = null
        private var cols = 0
        private var rows = 0
        private val paint = Paint().apply { isAntiAlias = false }

        fun setVisual(v: Visual) {
            rect = Rect(v.rect)
            if (colors !== v.colors) {
                colors = v.colors; cols = v.cols; rows = v.rows
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val c = colors
            if (c == null || cols <= 0 || rows <= 0) {
                canvas.drawColor(Color.BLACK)
                return
            }
            val w = width.toFloat()
            val h = height.toFloat()
            for (y in 0 until rows) {
                val top = h * y / rows
                val bottom = h * (y + 1) / rows
                for (x in 0 until cols) {
                    paint.color = c[y * cols + x]
                    canvas.drawRect(w * x / cols, top, w * (x + 1) / cols, bottom, paint)
                }
            }
        }
    }
}
