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
        private const val PEEK_MS = 60L         // how long a box is lifted to look underneath

        @Volatile var instance: CaptureService? = null
            private set
        val running: Boolean get() = instance != null
        @Volatile var lastInferMs: Long = 0
        @Volatile var lastBoxCount: Int = 0
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

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
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
        main.post { if (!stopped) overlay.clear() }
        Log.i(TAG, "capture ${w}x$h for screen ${screenW}x$screenH")
    }

    private fun roundTo32(v: Float): Int = max(32, ((v + 16f) / 32f).toInt() * 32)

    // ---------------------------------------------------------------- main loop

    private val tick: Runnable = Runnable {
        if (stopped) return@Runnable
        val start = SystemClock.uptimeMillis()
        try {
            if (screenOn) processFrame()
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
            lastError = t.toString()
        }
        val interval = 1000L / prefs.checksPerSecond.coerceIn(1, 15)
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
        // null = nothing on screen changed since the last check -> boxes stay as they are.
        val img = r.acquireLatestImage() ?: return
        val ok = try { readImage(img) } finally { img.close() }
        if (!ok) return

        val dets = runDetector(det)
        val now = SystemClock.uptimeMillis()
        val p = params()
        val toVerify = tracker.update(dets, grid, gridW, gridH, now, p, emptySet(), sampler)
        pushOverlay()
        if (toVerify.isNotEmpty()) verify(toVerify, det, p)
    }

    /** Lift the given boxes for a moment, capture what's underneath, and keep only real ones. */
    private fun verify(ids: Set<Int>, det: Detector, p: BoxTracker.Params) {
        val r = reader ?: return
        runOnMainAndWait { overlay.setHidden(ids, true) }
        SystemClock.sleep(PEEK_MS)
        var img: Image? = r.acquireLatestImage()
        if (img == null) { SystemClock.sleep(30); img = r.acquireLatestImage() }
        main.post { if (!stopped) overlay.setHidden(ids, false) }
        if (img == null) return
        val ok = try { readImage(img) } finally { img.close() }
        if (!ok) return
        val dets = runDetector(det)
        tracker.update(dets, grid, gridW, gridH, SystemClock.uptimeMillis(), p, ids, sampler)
        pushOverlay()
    }

    private fun runDetector(det: Detector): List<android.graphics.RectF> {
        val t0 = SystemClock.uptimeMillis()
        val res = det.detect(
            tensor, vdW, vdH,
            prefs.thresholdPct / 100f,
            Detector.enabledClasses(prefs.coverMaleChest),
        )
        lastInferMs = SystemClock.uptimeMillis() - t0
        return res.map { it.rect }
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
    private val sampler: (android.graphics.RectF) -> BoxTracker.Mosaic = { r ->
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

    private fun pushOverlay() {
        val sx = screenW.toFloat() / vdW
        val sy = screenH.toFloat() / vdH
        val pixelate = prefs.pixelate
        val map = HashMap<Int, OverlayManager.Visual>()
        for (b in tracker.boxes) {
            val r = Rect(
                floor(b.rect.left * sx).toInt(),
                floor(b.rect.top * sy).toInt(),
                ceil(b.rect.right * sx).toInt(),
                ceil(b.rect.bottom * sy).toInt(),
            )
            val m = if (pixelate) b.mosaic else null
            map[b.id] = OverlayManager.Visual(r, m?.colors, m?.cols ?: 0, m?.rows ?: 0)
        }
        lastBoxCount = map.size
        main.post { if (!stopped) overlay.apply(map) }
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
        if (::overlay.isInitialized) overlay.release()
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        lastBoxCount = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ToggleTileService.refresh(this)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
