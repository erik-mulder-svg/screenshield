package app.screenshield

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import java.util.function.Consumer

/**
 * One small overlay window per censor box ("display over other apps").
 * A real Window (via Dialog) is used so blur mode can use the system's
 * cross-window background blur, which blurs exactly what is behind the box.
 *
 * Must only be used on the main thread.
 */
class OverlayManager(context: Context, blur: Boolean) {

    private val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_NoActionBar)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val windows = HashMap<Int, Dialog>()
    private val rects = HashMap<Int, Rect>()

    var blur: Boolean = blur
        set(v) { if (field != v) { field = v; recreateAll() } }

    private val blurListener = Consumer<Boolean> { recreateAll() }

    init {
        wm.addCrossWindowBlurEnabledListener(themed.mainExecutor, blurListener)
    }

    /** Show exactly these boxes (screen pixels), keyed by box id. */
    fun apply(boxes: Map<Int, Rect>) {
        val gone = windows.keys.filter { it !in boxes }
        for (id in gone) remove(id)
        for ((id, r) in boxes) {
            val d = windows[id] ?: create().also { windows[id] = it }
            if (rects[id] != r) {
                position(d, r)
                rects[id] = Rect(r)
            }
            d.window?.decorView?.visibility = View.VISIBLE
        }
    }

    fun setHidden(ids: Set<Int>, hidden: Boolean) {
        for (id in ids) {
            windows[id]?.window?.decorView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        }
    }

    fun clear() {
        for (id in windows.keys.toList()) remove(id)
    }

    fun release() {
        clear()
        try { wm.removeCrossWindowBlurEnabledListener(blurListener) } catch (_: Throwable) {}
    }

    private fun recreateAll() {
        val saved = HashMap(rects)
        clear()
        apply(saved)
    }

    private fun remove(id: Int) {
        windows.remove(id)?.let { try { it.dismiss() } catch (_: Throwable) {} }
        rects.remove(id)
    }

    private fun create(): Dialog {
        val d = Dialog(themed)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setCancelable(false)
        d.setContentView(View(themed))
        val w = d.window!!
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        w.setWindowAnimations(0)
        if (blur && wm.isCrossWindowBlurEnabled) {
            w.setFormat(PixelFormat.TRANSLUCENT)
            w.setBackgroundDrawable(ColorDrawable(0x55000000))
            w.setBackgroundBlurRadius(BLUR_RADIUS)
        } else if (blur) {
            // Blur switched off by the system (e.g. power saving): opaque grey instead.
            w.setBackgroundDrawable(ColorDrawable(0xFF3C3C3C.toInt()))
        } else {
            w.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
        }
        w.decorView.setPadding(0, 0, 0, 0)
        val lp = w.attributes
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        lp.fitInsetsSides = 0
        lp.width = 1; lp.height = 1; lp.x = 0; lp.y = 0
        lp.title = "ScreenShield box"
        w.attributes = lp
        d.show()
        return d
    }

    private fun position(d: Dialog, r: Rect) {
        val w = d.window ?: return
        val lp = w.attributes
        lp.x = r.left
        lp.y = r.top
        lp.width = r.width().coerceAtLeast(1)
        lp.height = r.height().coerceAtLeast(1)
        w.attributes = lp
    }

    companion object {
        private const val BLUR_RADIUS = 120
    }
}
