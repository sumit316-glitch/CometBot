package com.cometbot

/**
 * Pure-Kotlin detector (no Android classes) so it can be unit-tested on a PC.
 * Input: RGBA bytes (R,G,B,A order, as delivered by ImageReader RGBA_8888).
 *
 * Targets  : "comets"  - tall narrow pink/orange tail with a WHITE head at the bottom.
 * Hazards  : "skulls"  - round orange blobs with a dark face. Never tapped.
 */
class Detector {
    class Comet(val hx: Float, val hy: Float, val x0: Int, val y0: Int, val w: Int, val h: Int)
    class Skull(val cx: Float, val cy: Float)
    class Result(val comets: List<Comet>, val skulls: List<Skull>, val skullR: Float)

    private var cap = 0
    private var hot = BooleanArray(0)
    private var white = BooleanArray(0)
    private var dark = BooleanArray(0)
    private var seen = BooleanArray(0)
    private var queue = IntArray(0)
    private var rr = IntArray(0); private var gg = IntArray(0); private var bb = IntArray(0)

    private fun ensure(n: Int) {
        if (n == cap) return
        cap = n
        hot = BooleanArray(n); white = BooleanArray(n); dark = BooleanArray(n); seen = BooleanArray(n)
        queue = IntArray(n); rr = IntArray(n); gg = IntArray(n); bb = IntArray(n)
    }

    fun detect(px: ByteArray, rowStride: Int, W: Int, H: Int): Result {
        ensure(W * H)
        val s = W / 480.0
        val s2 = s * s
        for (y in 0 until H) {
            var p = y * rowStride
            for (x in 0 until W) {
                val r = px[p].toInt() and 0xFF
                val g = px[p + 1].toInt() and 0xFF
                val b = px[p + 2].toInt() and 0xFF
                p += 4
                val i = y * W + x
                rr[i] = r; gg[i] = g; bb[i] = b
                hot[i] = r > 200 && (r - b) > 40
                white[i] = r > 225 && g > 215 && b > 190
            }
        }

        // --- fill holes of `hot`: flood the outside (4-neighbour) over non-hot pixels
        java.util.Arrays.fill(seen, false)
        var qh = 0; var qt = 0
        fun push(i: Int) { if (!hot[i] && !seen[i]) { seen[i] = true; queue[qt++] = i } }
        for (x in 0 until W) { push(x); push((H - 1) * W + x) }
        for (y in 0 until H) { push(y * W); push(y * W + W - 1) }
        while (qh < qt) {
            val i = queue[qh++]; val x = i % W; val y = i / W
            if (x > 0) push(i - 1); if (x < W - 1) push(i + 1)
            if (y > 0) push(i - W); if (y < H - 1) push(i + W)
        }
        // dark pixels inside holes = skull faces
        for (i in 0 until W * H) {
            dark[i] = !hot[i] && !seen[i] && rr[i] < 110 && gg[i] < 90 && bb[i] < 140
        }
        val skulls = ArrayList<Skull>()
        java.util.Arrays.fill(seen, false)
        for (start in 0 until W * H) {
            if (!dark[start] || seen[start]) continue
            qh = 0; qt = 0; seen[start] = true; queue[qt++] = start
            var sx = 0L; var sy = 0L
            while (qh < qt) {
                val i = queue[qh++]; val x = i % W; val y = i / W
                sx += x; sy += y
                if (x > 0 && dark[i - 1] && !seen[i - 1]) { seen[i - 1] = true; queue[qt++] = i - 1 }
                if (x < W - 1 && dark[i + 1] && !seen[i + 1]) { seen[i + 1] = true; queue[qt++] = i + 1 }
                if (y > 0 && dark[i - W] && !seen[i - W]) { seen[i - W] = true; queue[qt++] = i - W }
                if (y < H - 1 && dark[i + W] && !seen[i + W]) { seen[i + W] = true; queue[qt++] = i + W }
            }
            if (qt >= 150 * s2) skulls.add(Skull(sx.toFloat() / qt, sy.toFloat() / qt))
        }
        val rs = (0.105 * W).toFloat()

        // --- erase skull discs from the hot mask so comets overlapping skulls separate
        val er = 1.05f * rs
        for (k in skulls) {
            val y0 = maxOf(0, (k.cy - er).toInt()); val y1 = minOf(H - 1, (k.cy + er).toInt())
            val x0 = maxOf(0, (k.cx - er).toInt()); val x1 = minOf(W - 1, (k.cx + er).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x - k.cx; val dy = y - k.cy
                if (dx * dx + dy * dy < er * er) hot[y * W + x] = false
            }
        }

        // --- label remaining hot components (8-neighbour), keep comet-shaped ones
        val comets = ArrayList<Comet>()
        java.util.Arrays.fill(seen, false)
        for (start in 0 until W * H) {
            if (!hot[start] || seen[start]) continue
            qh = 0; qt = 0; seen[start] = true; queue[qt++] = start
            var minX = W; var maxX = 0; var minY = H; var maxY = 0
            while (qh < qt) {
                val i = queue[qh++]; val x = i % W; val y = i / W
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue
                    val j = ny * W + nx
                    if (hot[j] && !seen[j]) { seen[j] = true; queue[qt++] = j }
                }
            }
            val w = maxX - minX + 1; val h = maxY - minY + 1
            val x1 = maxX + 1; val y1 = maxY + 1
            if (qt < 150 * s2 || h < 1.4 * w || h < 0.04 * H || w > 0.14 * W) continue
            if (minX <= 1 || x1 >= W - 1) continue
            var bot = 0; var top = 0
            val botY0 = y1 - (h * 0.3).toInt(); val botY1 = minOf(H - 1, y1 + 3)
            for (y in botY0..botY1) for (x in minX until x1) if (white[y * W + x]) bot++
            for (y in minY until minY + (h * 0.3).toInt()) for (x in minX until x1) if (white[y * W + x]) top++
            if (bot < 25 * s2 || top > bot * 0.5) continue
            var hx = 0.0; var hy = 0.0; var hn = 0
            for (y in minY..minOf(H - 1, y1 + 3)) {
                if ((y - minY) <= h * 0.6) continue
                for (x in minX until x1) if (white[y * W + x]) { hx += x; hy += y; hn++ }
            }
            if (hn == 0) continue
            comets.add(Comet((hx / hn).toFloat(), (hy / hn).toFloat(), minX, minY, w, h))
        }
        return Result(comets, skulls, rs)
    }
}
