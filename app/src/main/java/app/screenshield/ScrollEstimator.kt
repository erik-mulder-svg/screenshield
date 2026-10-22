package app.screenshield

import kotlin.math.abs

/**
 * Estimates vertical scrolling between two consecutive frames by sliding the
 * previous coarse luma grid over the current one and finding the best match.
 *
 * Cells covered by our own overlay windows are ignored (they don't move with
 * the content), and only the middle band of the screen is used so fixed
 * status/app/navigation bars don't pull the estimate towards zero.
 */
class ScrollEstimator {

    enum class Kind { STATIC, SCROLL, CHANGED, UNKNOWN }

    class Result(val kind: Kind, val shiftCells: Float)

    private var prev: IntArray? = null
    private var prevMask: BooleanArray? = null
    private var gw = 0
    private var gh = 0

    fun reset() { prev = null; prevMask = null }

    /**
     * @param grid current luma grid (gw x gh)
     * @param mask true where our own windows covered the cell in this frame
     * @return shift in cells: content moved by [Result.shiftCells] rows (negative = moved up).
     */
    fun estimate(grid: IntArray, mask: BooleanArray, w: Int, h: Int): Result {
        val p = prev
        val pm = prevMask
        val sameSize = p != null && pm != null && gw == w && gh == h && p.size == grid.size
        val result = if (!sameSize) Result(Kind.UNKNOWN, 0f) else search(grid, mask, p!!, pm!!, w, h)
        if (prev == null || prev!!.size != grid.size) { prev = IntArray(grid.size); prevMask = BooleanArray(grid.size) }
        System.arraycopy(grid, 0, prev!!, 0, grid.size)
        System.arraycopy(mask, 0, prevMask!!, 0, mask.size)
        gw = w; gh = h
        return result
    }

    private fun search(cur: IntArray, cm: BooleanArray, p: IntArray, pm: BooleanArray, w: Int, h: Int): Result {
        val y0 = (h * 0.10f).toInt()
        val y1 = (h * 0.90f).toInt()
        val maxShift = minOf(MAX_SHIFT, (y1 - y0) / 2)
        val costs = FloatArray(2 * maxShift + 1) { Float.MAX_VALUE }

        fun cost(s: Int): Float {
            var sum = 0L
            var n = 0
            var y = y0
            while (y < y1) {
                val py = y - s            // content now at row y was at row y - s before
                if (py in 0 until h) {
                    val r = y * w
                    val pr = py * w
                    var x = 1
                    while (x < w) {
                        if (!cm[r + x] && !pm[pr + x]) {
                            sum += abs(cur[r + x] - p[pr + x]); n++
                        }
                        x += 2
                    }
                }
                y += 1
            }
            val minCells = ((y1 - y0) * w / 2) / 5
            return if (n < minCells) Float.MAX_VALUE else sum.toFloat() / n
        }

        // Coarse pass (every 2nd shift), then refine around the best one.
        var best = 0
        var bestCost = Float.MAX_VALUE
        var s = -maxShift
        while (s <= maxShift) {
            val c = cost(s); costs[s + maxShift] = c
            if (c < bestCost) { bestCost = c; best = s }
            s += 2
        }
        for (d in intArrayOf(-1, 1)) {
            val t = best + d
            if (t < -maxShift || t > maxShift) continue
            val c = cost(t); costs[t + maxShift] = c
            if (c < bestCost) { bestCost = c; best = t }
        }
        if (bestCost == Float.MAX_VALUE) return Result(Kind.UNKNOWN, 0f)

        val zero = costs[maxShift].let { if (it == Float.MAX_VALUE) cost(0) else it }
        if (zero < STATIC_COST) return Result(Kind.STATIC, 0f)
        if (bestCost > MATCH_COST || best == 0 || bestCost > zero * 0.6f) {
            return Result(Kind.CHANGED, 0f)
        }

        // Sub-cell refinement with a parabola through the neighbours.
        var frac = 0f
        val lo = best - 1; val hi = best + 1
        if (lo >= -maxShift && hi <= maxShift) {
            val cl = costs[lo + maxShift].let { if (it == Float.MAX_VALUE) cost(lo) else it }
            val ch = costs[hi + maxShift].let { if (it == Float.MAX_VALUE) cost(hi) else it }
            val denom = cl - 2 * bestCost + ch
            if (cl != Float.MAX_VALUE && ch != Float.MAX_VALUE && denom > 1e-3f) {
                frac = (0.5f * (cl - ch) / denom).coerceIn(-0.5f, 0.5f)
            }
        }
        return Result(Kind.SCROLL, best + frac)
    }

    companion object {
        private const val MAX_SHIFT = 110      // cells (x4 capture px) ≈ a full screen at 12 checks/s
        private const val STATIC_COST = 1.5f   // mean luma diff below this = nothing moved
        private const val MATCH_COST = 14f     // best match worse than this = content changed, not scrolled
    }
}
