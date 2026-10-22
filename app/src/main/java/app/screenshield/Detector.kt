package app.screenshield

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.RectF
import android.util.Log
import java.nio.FloatBuffer

/**
 * Wrapper around the NudeNet v3 "320n" detector (YOLOv8n, ONNX).
 * The model accepts any input size that is a multiple of 32, so the whole
 * (portrait or landscape) screen is checked in a single pass.
 *
 * Output: [1, 4 + 18, N] -> cx, cy, w, h (input pixels) followed by 18 class scores.
 */
class Detector(context: Context) : AutoCloseable {

    data class Detection(val rect: RectF, val score: Float, val cls: Int)

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String

    init {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        session = createSession(bytes)
        inputName = session.inputNames.first()
    }

    private fun createSession(bytes: ByteArray): OrtSession {
        try {
            val o = OrtSession.SessionOptions()
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            o.setIntraOpNumThreads(1)
            o.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            return env.createSession(bytes, o)
        } catch (t: Throwable) {
            Log.w(TAG, "XNNPACK unavailable, falling back to plain CPU", t)
        }
        val o = OrtSession.SessionOptions()
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        o.setIntraOpNumThreads(4)
        return env.createSession(bytes, o)
    }

    /**
     * @param input CHW float RGB in 0..1, size 3*h*w, w and h multiples of 32.
     * @param enabled which of the 18 classes should be reported (only when they win the argmax).
     * @param ownThresholds per class: if > 0, that class is reported whenever its OWN score reaches
     *        this value, even if another class scores higher (used for feet + faces, not for nudity,
     *        where "covered" must be allowed to win so swimwear isn't censored).
     */
    fun detect(
        input: FloatBuffer, w: Int, h: Int, threshold: Float, enabled: BooleanArray,
        ownThresholds: FloatArray? = null,
    ): List<Detection> {
        input.rewind()
        val raw = ArrayList<Detection>()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val shape = out.info.shape
                val channels = shape[1].toInt()
                val n = shape[2].toInt()
                val fb = out.floatBuffer
                val a = FloatArray(fb.remaining())
                fb.get(a)
                val numClasses = channels - 4
                for (i in 0 until n) {
                    // Same rule as NudeNet: the best class over ALL classes decides,
                    // so e.g. "breast covered" (swimwear) beats "breast exposed".
                    var best = -1
                    var bestScore = 0f
                    for (c in 0 until numClasses) {
                        val s = a[(4 + c) * n + i]
                        if (s > bestScore) { bestScore = s; best = c }
                    }
                    val cx = a[i]; val cy = a[n + i]; val bw = a[2 * n + i]; val bh = a[3 * n + i]
                    if (ownThresholds != null) {
                        for (c in 0 until minOf(numClasses, ownThresholds.size)) {
                            val t = ownThresholds[c]
                            if (t <= 0f) continue
                            val s = a[(4 + c) * n + i]
                            if (s >= t) raw += Detection(RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), s, c)
                        }
                    }
                    if (best < 0 || bestScore < threshold) continue
                    if (best >= enabled.size || !enabled[best]) continue
                    if (ownThresholds != null && best < ownThresholds.size && ownThresholds[best] > 0f) continue
                    raw += Detection(RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), bestScore, best)
                }
            }
        }
        return nms(raw, 0.45f)
    }

    private fun nms(dets: List<Detection>, iouThr: Float): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val keep = ArrayList<Detection>()
        for (d in sorted) {
            if (keep.none { it.cls == d.cls && iou(it.rect, d.rect) > iouThr }) keep += d
            if (keep.size >= 40) break
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = maxOf(a.left, b.left); val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right); val btm = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, r - l) * maxOf(0f, btm - t)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val TAG = "Detector"
        const val MODEL_ASSET = "320n.onnx"

        val LABELS = arrayOf(
            "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED",
            "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED",
            "ANUS_EXPOSED", "FEET_EXPOSED", "BELLY_COVERED", "FEET_COVERED",
            "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE", "BELLY_EXPOSED",
            "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED", "BUTTOCKS_COVERED",
        )

        /** Exposed genitals, breasts, buttocks (and anus). Covered/swimwear classes are never censored. */
        fun enabledClasses(coverMaleChest: Boolean): BooleanArray {
            val e = BooleanArray(LABELS.size)
            for (name in listOf(
                "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED",
                "MALE_GENITALIA_EXPOSED", "ANUS_EXPOSED",
            )) e[LABELS.indexOf(name)] = true
            if (coverMaleChest) e[LABELS.indexOf("MALE_BREAST_EXPOSED")] = true
            return e
        }

        val FEET_EXPOSED = LABELS.indexOf("FEET_EXPOSED")
        val FACE_FEMALE = LABELS.indexOf("FACE_FEMALE")
    }
}
