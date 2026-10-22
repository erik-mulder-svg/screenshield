package app.screenshield

import android.graphics.RectF

/**
 * "Censor check" pre-blocking below women's faces.
 *
 * Idea: when scrolling a feed, a picture's face appears before the body. So
 * as soon as a female face is detected, everything below it that the detector
 * has NOT analysed yet gets blocked. The block is lifted (after a quick look
 * underneath) once that area has been checked and any nudity is censored.
 *
 * Each guard remembers [analysedBottom]: down to which point (in content
 * coordinates that scroll along with the page) the area below the face has
 * been seen by the detector. Only the part below that line is blocked.
 * All coordinates are capture-frame pixels.
 */
class FaceGuards {

    class Guard(val id: Int, var face: RectF, var analysedBottom: Float, var lastSeen: Long) {
        /** Since when part of this guard has been visible on screen and blocking. */
        var blockingSince: Long = 0L
    }

    val guards = ArrayList<Guard>()
    private var nextId = 1

    fun clear() = guards.clear()

    /** The area below the face that can contain the body (not clipped to the frame). */
    fun region(g: Guard): RectF {
        val f = g.face
        val w = f.width()
        val h = f.height()
        return RectF(f.centerX() - 2.3f * w, f.bottom - 0.05f * h, f.centerX() + 2.3f * w, f.bottom + 8f * h)
    }

    /**
     * The part that still has to be checked and is on screen (empty rect = nothing to block).
     * [smear] widens it in the scroll direction to cover where the content will be until the next check.
     */
    fun blockRect(g: Guard, frameW: Int, frameH: Int, smear: Float = 0f): RectF {
        val r = region(g)
        var top = maxOf(r.top, g.analysedBottom)
        var bottom = r.bottom
        if (top >= bottom) return RectF()
        if (smear < 0f) top += smear else bottom += smear
        top = maxOf(top, 0f)
        bottom = minOf(bottom, frameH.toFloat())
        val left = maxOf(r.left, 0f)
        val right = minOf(r.right, frameW.toFloat())
        return if (top >= bottom - 2f || left >= right) RectF() else RectF(left, top, right, bottom)
    }

    fun shift(cells: Float, cell: Int) {
        if (cells == 0f) return
        val dy = cells * cell
        for (g in guards) { g.face.offset(0f, dy); g.analysedBottom += dy }
    }

    /**
     * New detections of female faces in a frame where the guards were NOT hidden.
     * Everything visible in this frame (outside guards) was analysed by this frame's detection.
     */
    fun update(faces: List<RectF>, now: Long, frameW: Int, frameH: Int, contentChanged: Boolean, holdMs: Long) {
        val matched = HashSet<Int>()
        for (f in faces) {
            val g = guards.firstOrNull { it.id !in matched && overlap(it.face, f) > 0.3f }
            if (g != null) {
                g.face = RectF(f); g.lastSeen = now; matched += g.id
            } else {
                // Everything on screen right now was just analysed (no guard covered it yet),
                // so only content that enters from below the screen edge needs blocking.
                val ng = Guard(nextId++, RectF(f), frameH.toFloat(), now)
                guards += ng; matched += ng.id
            }
        }
        val it = guards.iterator()
        while (it.hasNext()) {
            val g = it.next()
            val r = region(g)
            val done = g.analysedBottom >= r.bottom
            val gone = r.bottom <= 0f || r.top >= frameH
            val faceOnScreen = g.face.top >= 0f && g.face.bottom <= frameH
            val lost = g.id !in matched && faceOnScreen &&
                (contentChanged || now - g.lastSeen > holdMs * 3)
            if (done || gone || lost) it.remove()
        }
    }

    /** The guards were lifted and this frame was analysed: everything on screen is now checked. */
    fun markAnalysed(ids: Set<Int>, frameH: Int) {
        for (g in guards) if (g.id in ids) g.analysedBottom = maxOf(g.analysedBottom, frameH.toFloat())
        guards.removeAll { it.analysedBottom >= region(it).bottom }
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val minArea = minOf(a.width() * a.height(), b.width() * b.height())
        return if (minArea <= 0f) 0f else w * h / minArea
    }
}
