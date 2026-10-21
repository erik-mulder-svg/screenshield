package app.screenshield

import android.graphics.RectF
import kotlin.math.abs

/**
 * Keeps the set of censor boxes stable between detector runs.
 *
 * The hard problem: our own boxes appear in the next screen capture, so the
 * detector can no longer see what is underneath them. Strategy:
 *  1. A box stays for [Params.holdMs] after its last sighting (hides lag/flicker).
 *  2. After that, if the content AROUND the box hasn't changed since the box was
 *     last confirmed (static image, paused video), the box is kept indefinitely.
 *  3. If the surroundings did change (scrolling, video playing), the box is
 *     "verified": it is hidden for a few tens of ms, a fresh frame is captured,
 *     and the box is kept only if the detector still finds something there.
 *
 * All coordinates are in capture-frame pixels.
 */
class BoxTracker {

    class Box(val id: Int, var rect: RectF, var lastSeen: Long, var ref: IntArray)

    data class Params(
        val enlargeFrac: Float,
        val holdMs: Long,
        val frameW: Int,
        val frameH: Int,
        val cell: Int,
    )

    val boxes = ArrayList<Box>()
    private var nextId = 1

    fun clear() = boxes.clear()

    /**
     * @param verifying ids of boxes that were hidden for this frame; if they are not
     *        re-detected now, they are removed.
     * @return ids of boxes that should be verified (peeked under).
     */
    fun update(
        dets: List<RectF>,
        grid: IntArray, gw: Int, gh: Int,
        now: Long, p: Params,
        verifying: Set<Int>,
    ): Set<Int> {
        val matched = HashSet<Int>()
        for (d in dets) {
            val e = enlarge(d, p)
            var best: Box? = null
            var bestScore = 0f
            for (b in boxes) {
                val s = overlap(b.rect, e)
                if (s > bestScore) { bestScore = s; best = b }
            }
            if (best != null && bestScore > 0.3f) {
                best.rect = stabilise(best.rect, e)
                best.lastSeen = now
                best.ref = grid.copyOf()
                matched += best.id
            } else {
                val nb = Box(nextId++, e, now, grid.copyOf())
                boxes += nb
                matched += nb.id
            }
        }

        val toVerify = HashSet<Int>()
        val it = boxes.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.id in matched) continue
            if (b.id in verifying) { it.remove(); continue }   // peeked, nothing there anymore
            if (now - b.lastSeen < p.holdMs) continue            // still within hold time
            if (!surroundingsChanged(b, grid, gw, gh, p)) {
                b.lastSeen = now                                 // static screen: keep covering
            } else {
                toVerify += b.id
            }
        }
        dedupe()
        return toVerify
    }

    /** Drop boxes that are almost entirely inside a bigger box. */
    private fun dedupe() {
        val remove = HashSet<Int>()
        for (a in boxes) for (b in boxes) {
            if (a === b || a.id in remove || b.id in remove) continue
            val areaA = a.rect.width() * a.rect.height()
            val areaB = b.rect.width() * b.rect.height()
            if (areaA <= areaB && interArea(a.rect, b.rect) > 0.9f * areaA) remove += a.id
        }
        if (remove.isNotEmpty()) boxes.removeAll { it.id in remove }
    }

    private fun enlarge(r: RectF, p: Params): RectF {
        val dx = r.width() * p.enlargeFrac
        val dy = r.height() * p.enlargeFrac
        return RectF(
            (r.left - dx).coerceAtLeast(0f),
            (r.top - dy).coerceAtLeast(0f),
            (r.right + dx).coerceAtMost(p.frameW.toFloat()),
            (r.bottom + dy).coerceAtMost(p.frameH.toFloat()),
        )
    }

    /** Avoid jitter: keep the old box if it already covers the new one and isn't much larger. */
    private fun stabilise(old: RectF, new: RectF): RectF {
        val oldArea = old.width() * old.height()
        val newArea = new.width() * new.height()
        return if (old.contains(new) && oldArea < newArea * 1.6f) old else new
    }

    private fun interArea(a: RectF, b: RectF): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    /** Intersection divided by the smaller area. */
    private fun overlap(a: RectF, b: RectF): Float {
        val minArea = minOf(a.width() * a.height(), b.width() * b.height())
        return if (minArea <= 0f) 0f else interArea(a, b) / minArea
    }

    private fun coveredByAnyBox(x: Float, y: Float): Boolean {
        for (b in boxes) if (b.rect.contains(x, y)) return true
        return false
    }

    /** Compares the luma ring around the box with the frame in which it was last confirmed. */
    private fun surroundingsChanged(b: Box, grid: IntArray, gw: Int, gh: Int, p: Params): Boolean {
        if (b.ref.size != grid.size) return true
        val c = p.cell
        val ix0 = (b.rect.left / c).toInt(); val iy0 = (b.rect.top / c).toInt()
        val ix1 = (b.rect.right / c).toInt(); val iy1 = (b.rect.bottom / c).toInt()
        val mx = maxOf(3, (ix1 - ix0) / 3); val my = maxOf(3, (iy1 - iy0) / 3)
        val ox0 = (ix0 - mx).coerceAtLeast(0); val oy0 = (iy0 - my).coerceAtLeast(0)
        val ox1 = (ix1 + mx).coerceAtMost(gw - 1); val oy1 = (iy1 + my).coerceAtMost(gh - 1)

        var n = 0
        var sum = 0L
        for (gy in oy0..oy1) for (gx in ox0..ox1) {
            if (gx in ix0..ix1 && gy in iy0..iy1) continue
            val px = gx * c + c / 2f; val py = gy * c + c / 2f
            if (coveredByAnyBox(px, py)) continue
            val i = gy * gw + gx
            sum += abs(grid[i] - b.ref[i]); n++
        }
        if (n < MIN_CELLS) {
            // Box (almost) fills the screen: fall back to everything that is visible.
            n = 0; sum = 0
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val px = gx * c + c / 2f; val py = gy * c + c / 2f
                if (coveredByAnyBox(px, py)) continue
                val i = gy * gw + gx
                sum += abs(grid[i] - b.ref[i]); n++
            }
            if (n < MIN_CELLS) return true   // nothing to compare with: verify periodically
        }
        return sum.toFloat() / n > CHANGE_THRESHOLD
    }

    companion object {
        private const val MIN_CELLS = 16
        private const val CHANGE_THRESHOLD = 6f   // mean luma difference (0..255)
    }
}
