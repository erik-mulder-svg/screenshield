package app.screenshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground service: mirrors the screen into a small in-memory ImageReader,
 * runs the detector and drives the overlay boxes.
 * Frames only ever live in RAM and are overwritten/closed immediately.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_STOP = "app.screenshield.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "protection"
        private const val NOTIF_ID = 1
        private const val CELL = 4              // luma grid cell size in capture pixels
        private const val FACE_THRESHOLD = 0.35f

        @Volatile var instance: CaptureService? = null
            private set
        val running: Boolean get() = instance != null
        @Volatile var lastInferMs: Long = 0
        @Volatile var lastBoxCount: Int = 0
        @Volatile var lastGuardCount: Int = 0
        @Volatile var lastScrollPxPerS: Float = 0f
        @Volatile var lastError: String? = null
    }

    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayManager
    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var wh: Handler? = null

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var detector: Detector? = null
    private val tracker = BoxTracker()

    // Capture geometry (worker thread only)
    private var vdW = 0
    private var vdH = 0
    private var screenW = 0
    private var screenH = 0
    private var curRes = 0
    private var floats = FloatArray(0)
    private var tensor: FloatBuffer = FloatBuffer.allocate(0)
    private var row = ByteArray(0)
    private var grid = IntArray(0)
    private var gridW = 0
    private var gridH = 0

    @Volatile private var screenOn = true
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (instance != null) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }

        createChannel()
        // Must be in the foreground with the mediaProjection type BEFORE getMediaProjection().
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (data == null) { stopEverything("No screen-capture permission"); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { mpm.getMediaProjection(code, data) } catch (t: Throwable) { null }
        if (p == null) { stopEverything("Screen capture was not granted"); return START_NOT_STICKY }

        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { shutdown() } }
        }, main)

        prefs = Prefs(this)
        overlay = OverlayManager(this)
        instance = this
        lastError = null

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)

        val t = HandlerThread("shield-detector", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        worker = t
        val h = Handler(t.looper)
        wh = h
        h.post {
            try {
                detector = Detector(this)
                setupDisplay()
                h.post(tick)
            } catch (e: Throwable) {
                Log.e(TAG, "start failed", e)
                lastError = "Start failed: ${e.message}"
                main.post { shutdown() }
            }
        }
        ToggleTileService.refresh(this)
        return START_NOT_STICKY
    }

    /** Called from the settings screen when something changed while running. */
    fun applySettings() {
        wh?.post { if (!stopped && prefs.resolution != curRes) setupDisplay() }
    }

    // ---------------------------------------------------------------- capture setup

    private fun setupDisplay() {
        val p = projection ?: return
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        screenW = bounds.width()
        screenH = bounds.height()
        curRes = prefs.resolution
        val scale = curRes.toFloat() / min(screenW, screenH)
        val w = roundTo32(screenW * scale)
        val h = roundTo32(screenH * scale)
        val dpi = max(1, (resources.displayMetrics.densityDpi * scale).roundToInt())

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        val existing = vd
        if (existing == null) {
            vd = p.createVirtualDisplay(
                "ScreenShield", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, null,
            )
        } else {
            // Android 14+: a projection may create only one virtual display; resize it instead.
            existing.resize(w, h, dpi)
            existing.surface = newReader.surface
        }
        reader?.close()
        reader = newReader
        vdW = w
        vdH = h

        floats = FloatArray(3 * w * h)
        tensor = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        row = ByteArray(w * 4)
        gridW = ceil(w / CELL.toFloat()).toInt()
        gridH = ceil(h / CELL.toFloat()).toInt()
        grid = IntArray(gridW * gridH)

        tracker.clear()
        highlights.clear()
        guards.clear()
        scroll.reset()
        shownRects = emptyList()
        velocity = 0f
        main.post { if (!stopped) overlay.clear() }
        Log.i(TAG, "capture ${w}x$h for screen ${screenW}x$screenH")
    }

    private fun roundTo32(v: Float): Int = max(32, ((v + 16f) / 32f).toInt() * 32)

    // ---------------------------------------------------------------- main loop

    private val scroll = ScrollEstimator()
    private val guards = FaceGuards()
    private var ownMask = BooleanArray(0)
    /** Rects (capture px) of our opaque windows as last shown; excluded from scroll matching. */
    private var shownRects: List<RectF> = emptyList()
    /** Scroll velocity in grid rows per ms (negative = content moving up). */
    private var velocity = 0f
    private var lastFrameNs = 0L
    private var lastFrameUptime = 0L
    private var lastMotionUptime = 0L
    private var lastGuardPeek = 0L

    private fun effectiveIntervalMs(now: Long): Long {
        val base = 1000L / prefs.checksPerSecond.coerceIn(1, 15)
        return if (prefs.boostWhileScrolling && now - lastMotionUptime < 800) min(base, 1000L / 12) else base
    }

    private val tick: Runnable = Runnable {
        if (stopped) return@Runnable
        val start = SystemClock.uptimeMillis()
        try {
            if (screenOn) processFrame()
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
            lastError = t.toString()
        }
        val interval = effectiveIntervalMs(SystemClock.uptimeMillis())
        val elapsed = SystemClock.uptimeMillis() - start
        wh?.postDelayed(tick, max(5L, interval - elapsed))
    }

    private fun params() = BoxTracker.Params(
        enlargeFrac = prefs.enlargePct / 100f,
        holdMs = prefs.holdMs.toLong(),
        frameW = vdW, frameH = vdH, cell = CELL,
    )

    private fun processFrame() {
        val r = reader ?: return
        val det = detector ?: return
        val now = SystemClock.uptimeMillis()
        // null = nothing on screen changed since the last check.
        val img = r.acquireLatestImage()
        if (img == null) {
            if (velocity != 0f && now - lastFrameUptime > 150) {
                velocity = 0f            // scrolling stopped: drop the motion smear
                pushOverlay(lastFrameNs)
            }
            maybePeekGuards(det, now)
            return
        }
        val ts = frameTimeNs(img)
        val ok = try { readImage(img) } finally { img.close() }
        if (!ok) return

        val kind = trackMotion(ts)
        val res = runDetector(det)
        val p = params()
        val toVerify = tracker.update(res.censor, grid, gridW, gridH, now, p, emptySet(), sampler)
        if (prefs.preBlockFaces) {
            guards.update(res.faces, now, vdW, vdH, kind == ScrollEstimator.Kind.CHANGED, p.holdMs)
        } else {
            guards.clear()
        }
        pushOverlay(ts)
        if (toVerify.isNotEmpty()) peek(det, toVerify, emptySet())
        else maybePeekGuards(det, SystemClock.uptimeMillis())
    }

    /** Lift "censor check" blocks for a moment to analyse what's under them, once scrolling slows. */
    private fun maybePeekGuards(det: Detector, now: Long) {
        if (!prefs.preBlockFaces || guards.guards.isEmpty()) return
        if (now - lastGuardPeek < 250) return
        val speed = abs(velocity) * CELL * (screenH.toFloat() / vdH) * 1000f   // screen px/s
        val ids = HashSet<Int>()
        for (g in guards.guards) {
            if (guards.blockRect(g, vdW, vdH).isEmpty) continue
            val waited = g.blockingSince != 0L && now - g.blockingSince > 1200
            if (speed < 250f || waited) ids += g.id
        }
        if (ids.isEmpty()) return
        lastGuardPeek = now
        peek(det, emptySet(), ids)
    }

    /**
     * Hide the given boxes/guards just long enough for ONE clean frame to be captured,
     * then analyse that frame. Boxes that aren't re-detected disappear; guarded areas
     * become "checked" and any nudity found there is boxed.
     */
    private fun peek(det: Detector, boxIds: Set<Int>, guardIds: Set<Int>) {
        val r = reader ?: return
        runOnMainAndWait {
            overlay.setHidden(OverlayManager.Kind.BOX, boxIds, true)
            overlay.setHidden(OverlayManager.Kind.GUARD, guardIds, true)
        }
        val img = waitForFreshImage(r, System.nanoTime())
        main.post {
            if (!stopped) {
                overlay.setHidden(OverlayManager.Kind.BOX, boxIds, false)
                overlay.setHidden(OverlayManager.Kind.GUARD, guardIds, false)
            }
        }
        if (img == null) return
        val ts = frameTimeNs(img)
        val ok = try { readImage(img) } finally { img.close() }
        if (!ok) return
        val kind = trackMotion(ts)
        val res = runDetector(det)
        val now = SystemClock.uptimeMillis()
        val p = params()
        tracker.update(res.censor, grid, gridW, gridH, now, p, boxIds, sampler)
        if (prefs.preBlockFaces) {
            if (guardIds.isNotEmpty()) guards.markAnalysed(guardIds, vdH)
            guards.update(res.faces, now, vdW, vdH, kind == ScrollEstimator.Kind.CHANGED, p.holdMs)
        }
        pushOverlay(ts)
    }

    /** Waits for the first frame composed after our windows were hidden (usually 15–35 ms). */
    private fun waitForFreshImage(r: ImageReader, hideNs: Long): Image? {
        val deadline = SystemClock.uptimeMillis() + 120
        var cand: Image? = null
        var seen = 0
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(4)
            val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: continue
            seen++
            cand?.close()
            cand = img
            val ts = img.timestamp
            val plausible = abs(ts - System.nanoTime()) < 1_000_000_000L
            if ((plausible && ts >= hideNs + 18_000_000L) || (!plausible && seen >= 2)) return img
        }
        return cand
    }

    private fun frameTimeNs(img: Image): Long {
        val ts = img.timestamp
        return if (abs(ts - System.nanoTime()) < 2_000_000_000L) ts else System.nanoTime()
    }

    /** Measures scrolling since the previous frame and moves everything along with the content. */
    private fun trackMotion(ts: Long): ScrollEstimator.Kind {
        if (ownMask.size != grid.size) ownMask = BooleanArray(grid.size)
        ownMask.fill(false)
        for (rc in shownRects) {
            val x0 = (rc.left / CELL).toInt().coerceAtLeast(0)
            val x1 = (rc.right / CELL).toInt().coerceAtMost(gridW - 1)
            val y0 = (rc.top / CELL).toInt().coerceAtLeast(0)
            val y1 = (rc.bottom / CELL).toInt().coerceAtMost(gridH - 1)
            for (y in y0..y1) for (x in x0..x1) ownMask[y * gridW + x] = true
        }
        val m = scroll.estimate(grid, ownMask, gridW, gridH)
        val dtMs = ((ts - lastFrameNs) / 1_000_000f).coerceIn(1f, 1000f)
        val now = SystemClock.uptimeMillis()
        when (m.kind) {
            ScrollEstimator.Kind.SCROLL -> {
                tracker.shift(m.shiftCells, CELL, vdH)
                guards.shift(m.shiftCells, CELL)
                val dy = m.shiftCells * CELL
                for (h in highlights) h.rect.offset(0f, dy)
                val v = m.shiftCells / dtMs
                velocity = if (velocity != 0f && (velocity > 0) == (v > 0)) 0.4f * velocity + 0.6f * v else v
                lastMotionUptime = now
            }
            else -> velocity = 0f
        }
        lastFrameNs = ts
        lastFrameUptime = now
        lastScrollPxPerS = abs(velocity) * CELL * (screenH.toFloat() / max(1, vdH)) * 1000f
        return m.kind
    }

    /** Highlighted feet: simple hold-based tracking (they're never covered, so no peeking needed). */
    private class Highlight(val id: Int, var rect: RectF, var lastSeen: Long)
    private val highlights = ArrayList<Highlight>()
    private var nextHighlightId = 1

    private class DetectResult(val censor: List<RectF>, val faces: List<RectF>)

    /** Runs the model once; splits results into censor areas and female faces, and updates [highlights]. */
    private fun runDetector(det: Detector): DetectResult {
        val t0 = SystemClock.uptimeMillis()
        val highlight = prefs.highlightFeet
        val preBlock = prefs.preBlockFaces
        val enabled = Detector.enabledClasses(prefs.coverMaleChest)
        val own = FloatArray(Detector.LABELS.size)
        if (highlight) own[Detector.FEET_EXPOSED] = prefs.feetThresholdPct / 100f
        if (preBlock) own[Detector.FACE_FEMALE] = FACE_THRESHOLD
        val res = det.detect(tensor, vdW, vdH, prefs.thresholdPct / 100f, enabled, own)
        lastInferMs = SystemClock.uptimeMillis() - t0

        val now = SystemClock.uptimeMillis()
        for (d in res) {
            if (d.cls != Detector.FEET_EXPOSED) continue
            val match = highlights.firstOrNull { RectF.intersects(it.rect, d.rect) }
            if (match != null) { match.rect = RectF(d.rect); match.lastSeen = now }
            else highlights += Highlight(nextHighlightId++, RectF(d.rect), now)
        }
        val hold = prefs.holdMs.toLong()
        highlights.removeAll { !highlight || now - it.lastSeen > hold || it.rect.bottom < 0 || it.rect.top > vdH }
        return DetectResult(
            censor = res.filter { it.cls != Detector.FEET_EXPOSED && it.cls != Detector.FACE_FEMALE }.map { it.rect },
            faces = res.filter { it.cls == Detector.FACE_FEMALE }.map { it.rect },
        )
    }

    /** Copies the frame into the model tensor + a coarse luma grid. The Image is closed by the caller. */
    private fun readImage(img: Image): Boolean {
        if (img.width != vdW || img.height != vdH) return false
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val w = vdW
        val h = vdH
        val area = w * h
        val need = (w - 1) * ps + 4
        if (row.size < need) row = ByteArray(need)
        val inv = 1f / 255f
        val half = CELL / 2
        for (y in 0 until h) {
            buf.position(y * rs)
            buf.get(row, 0, min(need, buf.remaining()))
            val base = y * w
            val gridRow = y % CELL == half && y / CELL < gridH
            for (x in 0 until w) {
                val i = x * ps
                val rr = row[i].toInt() and 0xFF
                val gg = row[i + 1].toInt() and 0xFF
                val bb = row[i + 2].toInt() and 0xFF
                val idx = base + x
                floats[idx] = rr * inv
                floats[area + idx] = gg * inv
                floats[2 * area + idx] = bb * inv
                if (gridRow && x % CELL == half) {
                    grid[(y / CELL) * gridW + x / CELL] = (rr * 77 + gg * 150 + bb * 29) shr 8
                }
            }
        }
        tensor.clear()
        tensor.put(floats)
        tensor.rewind()
        return true
    }

    /** Averages the current frame into a coarse mosaic (~7 blocks across the short side). */
    private val sampler: (RectF) -> BoxTracker.Mosaic = { r ->
        val x0 = r.left.toInt().coerceIn(0, vdW - 1)
        val y0 = r.top.toInt().coerceIn(0, vdH - 1)
        val x1 = r.right.toInt().coerceIn(x0 + 1, vdW)
        val y1 = r.bottom.toInt().coerceIn(y0 + 1, vdH)
        val bw = x1 - x0
        val bh = y1 - y0
        val block = max(3f, min(bw, bh) / 7f)
        val cols = max(1, (bw / block).roundToInt())
        val rows = max(1, (bh / block).roundToInt())
        val area = vdW * vdH
        val colors = IntArray(cols * rows)
        for (cy in 0 until rows) for (cx in 0 until cols) {
            val sx0 = x0 + bw * cx / cols; val sx1 = max(sx0 + 1, x0 + bw * (cx + 1) / cols)
            val sy0 = y0 + bh * cy / rows; val sy1 = max(sy0 + 1, y0 + bh * (cy + 1) / rows)
            var rs = 0f; var gs = 0f; var bs = 0f; var n = 0
            var yy = sy0
            while (yy < sy1) {
                var xx = sx0
                while (xx < sx1) {
                    val i = yy * vdW + xx
                    rs += floats[i]; gs += floats[area + i]; bs += floats[2 * area + i]; n++
                    xx += 2
                }
                yy += 2
            }
            colors[cy * cols + cx] = android.graphics.Color.rgb(
                (rs / n * 255).toInt(), (gs / n * 255).toInt(), (bs / n * 255).toInt(),
            )
        }
        BoxTracker.Mosaic(colors, cols, rows)
    }

    private fun toScreen(r: RectF, sx: Float, sy: Float) = Rect(
        floor(r.left * sx).toInt(), floor(r.top * sy).toInt(),
        ceil(r.right * sx).toInt(), ceil(r.bottom * sy).toInt(),
    )

    /**
     * Shows the current state. While scrolling, boxes and checks are stretched in the
     * scroll direction so they keep covering the content until the next check
     * ("smear"), instead of lagging behind it.
     */
    private fun pushOverlay(frameNs: Long) {
        val sx = screenW.toFloat() / vdW
        val sy = screenH.toFloat() / vdH
        val now = SystemClock.uptimeMillis()
        val lagMs = ((System.nanoTime() - frameNs) / 1_000_000f).coerceIn(0f, 500f)
        val interval = effectiveIntervalMs(now).toFloat()
        val smear = velocity * CELL * (lagMs + interval + 40f) * 1.15f      // capture px
        val predict = velocity * CELL * (lagMs + interval / 2f)

        val shown = ArrayList<RectF>()
        val pixelate = prefs.pixelate
        val boxes = HashMap<Int, OverlayManager.Visual>()
        for (b in tracker.boxes) {
            val r = RectF(b.rect)
            if (smear < 0f) r.top += smear else r.bottom += smear
            r.top = max(r.top, 0f); r.bottom = min(r.bottom, vdH.toFloat())
            if (r.height() < 1f) continue
            shown += r
            val m = if (pixelate) b.mosaic else null
            boxes[b.id] = OverlayManager.Visual(toScreen(r, sx, sy), m?.colors, m?.cols ?: 0, m?.rows ?: 0)
        }
        lastBoxCount = boxes.size

        val guardRects = HashMap<Int, Rect>()
        for (g in guards.guards) {
            val r = guards.blockRect(g, vdW, vdH, smear)
            if (r.isEmpty) { g.blockingSince = 0L; continue }
            if (g.blockingSince == 0L) g.blockingSince = now
            shown += r
            guardRects[g.id] = toScreen(r, sx, sy)
        }
        lastGuardCount = guardRects.size

        val hl = HashMap<Int, Rect>()
        for (h in highlights) hl[h.id] = toScreen(RectF(h.rect).apply { offset(0f, predict) }, sx, sy)

        shownRects = shown
        val touchThrough = prefs.guardTouchThrough
        main.post {
            if (!stopped) {
                overlay.applyBoxes(boxes)
                overlay.applyGuards(guardRects, touchThrough)
                overlay.applyHighlights(hl)
            }
        }
    }

    private fun runOnMainAndWait(block: () -> Unit) {
        val latch = CountDownLatch(1)
        main.post { try { if (!stopped) block() } finally { latch.countDown() } }
        latch.await(300, TimeUnit.MILLISECONDS)
    }

    // ---------------------------------------------------------------- system events

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            wh?.post {
                if (stopped) return@post
                val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                if (b.width() != screenW || b.height() != screenH) setupDisplay()
            }
        }
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Protection active", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("ScreenShield is on")
            .setContentText("Checking the screen on-device. Nothing is stored or sent.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    // ---------------------------------------------------------------- teardown

    private fun stopEverything(reason: String) {
        lastError = reason
        stopped = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ToggleTileService.refresh(this)
    }

    fun shutdown() {
        if (stopped) return
        stopped = true
        instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: Throwable) {}
        try { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) } catch (_: Throwable) {}

        val h = wh
        val t = worker
        if (h != null && t != null) {
            h.removeCallbacksAndMessages(null)
            h.post {
                try { vd?.release() } catch (_: Throwable) {}
                try { reader?.close() } catch (_: Throwable) {}
                try { detector?.close() } catch (_: Throwable) {}
                vd = null; reader = null; detector = null
                // Wipe the last frame from memory.
                floats.fill(0f); grid.fill(0)
                t.quitSafely()
            }
        }
        if (::overlay.isInitialized) overlay.clear()
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        lastBoxCount = 0
        lastGuardCount = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ToggleTileService.refresh(this)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
