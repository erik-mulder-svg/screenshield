package app.screenshield

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.random.Random

/**
 * All overlay windows ("display over other apps"):
 *  - BOX: censor boxes. Touchable, so Android keeps them 100% opaque
 *    (click-through overlays are capped at 80% opacity since Android 12).
 *  - GUARD: "censor check" blocks below faces. Either solid (opaque, swallows
 *    touches) or touch-through (lets you scroll, but max 80% opaque).
 *  - HIGHLIGHT: neon outline around bare feet. Click-through, transparent inside.
 *
 * Must only be used on the main thread.
 */
class OverlayManager(private val context: Context) {

    enum class Kind { BOX, GUARD, HIGHLIGHT }

    /** What to draw for one censor box. [colors] is a cols x rows mosaic, or null for solid black. */
    class Visual(val rect: Rect, val colors: IntArray?, val cols: Int, val rows: Int)

    private class Entry(val view: View, var rect: Rect, var touchThrough: Boolean)

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val entries = HashMap<Long, Entry>()

    private fun key(kind: Kind, id: Int): Long = (kind.ordinal.toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)
    private fun kindOf(key: Long): Int = (key ushr 32).toInt()

    // ------------------------------------------------------------------ public API

    fun applyBoxes(boxes: Map<Int, Visual>) {
        sync(Kind.BOX, boxes.keys)
        for ((id, v) in boxes) {
            val view = place(Kind.BOX, id, v.rect, touchThrough = false) { BoxView(context) } as BoxView
            view.setVisual(v)
        }
    }

    fun applyGuards(guards: Map<Int, Rect>, touchThrough: Boolean) {
        sync(Kind.GUARD, guards.keys)
        for ((id, r) in guards) {
            val k = key(Kind.GUARD, id)
            val e = entries[k]
            if (e != null && e.touchThrough != touchThrough) remove(k)   // style changed: recreate
            place(Kind.GUARD, id, r, touchThrough) { GuardView(context) }
        }
    }

    fun applyHighlights(areas: Map<Int, Rect>) {
        sync(Kind.HIGHLIGHT, areas.keys)
        val pad = (HL_PAD_DP * density).toInt()
        for ((id, r) in areas) {
            val padded = Rect(r).apply { inset(-pad, -pad) }
            place(Kind.HIGHLIGHT, id, padded, touchThrough = true) { HighlightView(context) }
        }
    }

    fun setHidden(kind: Kind, ids: Set<Int>, hidden: Boolean) {
        for (id in ids) entries[key(kind, id)]?.view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    fun clear() {
        for (k in entries.keys.toList()) remove(k)
    }

    // ------------------------------------------------------------------ window plumbing

    private fun sync(kind: Kind, keep: Set<Int>) {
        for (k in entries.keys.toList()) {
            if (kindOf(k) == kind.ordinal && (k and 0xFFFFFFFFL).toInt() !in keep) remove(k)
        }
    }

    private fun place(kind: Kind, id: Int, r: Rect, touchThrough: Boolean, make: () -> View): View {
        val k = key(kind, id)
        val e = entries[k]
        if (e == null) {
            val v = make()
            try {
                wm.addView(v, params(r, touchThrough, kind.name))
                entries[k] = Entry(v, Rect(r), touchThrough)
            } catch (_: Throwable) {
            }
            return v
        }
        if (e.rect != r) {
            try { wm.updateViewLayout(e.view, params(r, touchThrough, kind.name)) } catch (_: Throwable) {}
            e.rect = Rect(r)
        }
        e.view.visibility = View.VISIBLE
        return e.view
    }

    private fun remove(k: Long) {
        entries.remove(k)?.let { try { wm.removeViewImmediate(it.view) } catch (_: Throwable) {} }
    }

    private fun params(r: Rect, touchThrough: Boolean, title: String): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        flags = flags or if (touchThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val lp = WindowManager.LayoutParams(
            r.width().coerceAtLeast(1),
            r.height().coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            if (touchThrough) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = r.left
        lp.y = r.top
        // Android caps click-through overlays at 80% opacity; ask for exactly that.
        lp.alpha = if (touchThrough) 0.8f else 1f
        lp.windowAnimations = 0
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        lp.fitInsetsSides = 0
        lp.title = "ScreenShield $title"
        return lp
    }

    // ------------------------------------------------------------------ views

    private class BoxView(context: Context) : View(context) {
        private var colors: IntArray? = null
        private var cols = 0
        private var rows = 0
        private val paint = Paint().apply { isAntiAlias = false }

        fun setVisual(v: Visual) {
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

    /** Dark, busy noise texture (hard to see through even at 80%) with a "Censor check" label. */
    private class GuardView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val noise = Paint().apply {
            shader = BitmapShader(noiseTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setScale(3f * d, 3f * d) })
            }
            isFilterBitmap = false
        }
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0101018.toInt() }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13 * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val label = "Censor check…"

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), noise)
            if (height < 30 * d || width < 120 * d) return
            val tw = text.measureText(label) + 24 * d
            val cx = width / 2f
            val cy = minOf(height / 2f, 28 * d)
            val r = RectF(cx - tw / 2, cy - 14 * d, cx + tw / 2, cy + 14 * d)
            canvas.drawRoundRect(r, 14 * d, 14 * d, pill)
            canvas.drawText(label, cx, cy + 4.5f * d, text)
        }

        companion object {
            private fun noiseTile(): Bitmap {
                val n = 48
                val px = IntArray(n * n)
                val rnd = Random(7)
                for (i in px.indices) {
                    val v = if (rnd.nextFloat() < 0.5f) rnd.nextInt(0, 40) else rnd.nextInt(90, 200)
                    px[i] = Color.rgb(v, v, (v * 1.1f).toInt().coerceAtMost(255))
                }
                return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
            }
        }
    }

    /** Neon pink tube with a soft glow; interior stays fully visible. */
    private class HighlightView(context: Context) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val glowLayers = listOf(
            18f to 0x14, 13f to 0x24, 9f to 0x40, 6f to 0x70,
        ).map { (w, a) ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = w * d
                color = (a shl 24) or NEON
                strokeJoin = Paint.Join.ROUND
            }
        }
        private val tube = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f * d; color = 0xFF000000.toInt() or NEON
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = 0xFFFFC8F4.toInt()
        }

        override fun onDraw(canvas: Canvas) {
            val i = HL_PAD_DP * d * 0.55f
            val r = RectF(i, i, width - i, height - i)
            val rad = 16 * d
            for (p in glowLayers) canvas.drawRoundRect(r, rad, rad, p)
            canvas.drawRoundRect(r, rad, rad, tube)
            canvas.drawRoundRect(r, rad, rad, core)
        }

        companion object {
            private const val NEON = 0xFF1FCE   // neon pink, RGB only
        }
    }

    companion object {
        private const val HL_PAD_DP = 16f
    }
}
