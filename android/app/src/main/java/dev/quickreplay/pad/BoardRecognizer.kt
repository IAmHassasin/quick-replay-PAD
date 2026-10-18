package dev.quickreplay.pad

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the board is on the screenshot: cell (r, c) is centred at [centerX]/[centerY]. */
data class BoardGrid(val left: Float, val top: Float, val cell: Float, val rows: Int, val cols: Int) {
    fun centerX(index: Int): Float = left + (index % cols + 0.5f) * cell
    fun centerY(index: Int): Float = top + (index / cols + 0.5f) * cell
}

sealed interface Recognition {
    data class Found(val board: Board, val grid: BoardGrid, val confidence: FloatArray) : Recognition

    data class Failed(val reason: String) : Recognition
}

/**
 * Finds a PAD board on a screenshot and reads its orbs. Pure Kotlin on ARGB pixels, so it runs in
 * JVM tests on saved screenshots.
 *
 * 1. Grid: orbs are bright, saturated discs; the background between them (visible at the cell
 *    corners) is dark or dull. On a downscaled chroma map (max - min of R, G, B), the search tries
 *    cell sizes (board at least half the screen width) and positions and keeps the grid whose cell
 *    centres are the most colourful compared with its cell corners. Coarse steps, then refined.
 * 2. Orbs: orb skins change the inside of an orb (e.g. a watermelon) but keep the attribute colour
 *    on the outer ring, so only a ring around each centre is sampled. Each saturated, bright pixel
 *    votes for the hue range of an attribute; a cell is known when one attribute clearly wins.
 *    Hearts are pink rounded squares, which the same ring catches.
 */
object BoardRecognizer {
    private const val TARGET_WIDTH = 270
    private const val SIZE_STEP = 1.01f
    private const val MIN_GRID_SCORE = 0.12f
    private const val MIN_CONFIDENCE = 0.5f
    private const val MIN_COLOURED_SHARE = 0.15f
    private const val RING_INNER = 0.33f
    private const val RING_OUTER = 0.44f

    /** Chroma (0..255) of the strongly coloured pixels that outline an orb. */
    private const val EDGE_CHROMA = 90

    fun recognize(pixels: IntArray, width: Int, height: Int, rows: Int, cols: Int): Recognition {
        require(pixels.size == width * height) { "pixels must have width * height entries" }
        Board.checkSize(rows, cols)
        val grid = findGrid(pixels, width, height, rows, cols) ?: return Recognition.Failed("No PAD board found on the screen")
        val cells = IntArray(rows * cols)
        val confidence = FloatArray(rows * cols)
        val unknown = ArrayList<String>()
        for (i in cells.indices) {
            val (code, share) = classify(pixels, width, height, grid.centerX(i), grid.centerY(i), grid.cell)
            cells[i] = code
            confidence[i] = share
            if (code < 0) unknown += cellLabel(i, cols)
        }
        if (unknown.isNotEmpty()) {
            return Recognition.Failed("Cannot read ${unknown.size} orb(s) (${unknown.take(5).joinToString()}${if (unknown.size > 5) ", ..." else ""})")
        }
        return Recognition.Found(Board(rows, cols, cells), grid, confidence)
    }

    // ---- 1. Grid --------------------------------------------------------------------------------

    /** Chroma map downscaled by [factor], as an integral image ((w + 1) x (h + 1)). */
    private class ChromaMap(pixels: IntArray, width: Int, height: Int, val factor: Int) {
        val w = width / factor
        val h = height / factor
        private val sum = DoubleArray((w + 1) * (h + 1))

        init {
            for (y in 0 until h) {
                var row = 0.0
                for (x in 0 until w) {
                    var acc = 0
                    for (dy in 0 until factor) {
                        val base = (y * factor + dy) * width + x * factor
                        for (dx in 0 until factor) acc += chroma(pixels[base + dx])
                    }
                    row += acc / (255.0 * factor * factor)
                    sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + row
                }
            }
        }

        /** Mean chroma (0..1) of the square of side [side] centred at (cx, cy), clipped to the map. */
        fun mean(cx: Float, cy: Float, side: Float): Float {
            val half = side / 2
            val x0 = (cx - half).roundToInt().coerceIn(0, w)
            val x1 = (cx + half).roundToInt().coerceIn(0, w)
            val y0 = (cy - half).roundToInt().coerceIn(0, h)
            val y1 = (cy + half).roundToInt().coerceIn(0, h)
            val area = (x1 - x0) * (y1 - y0)
            if (area <= 0) return 0f
            val s = sum[y1 * (w + 1) + x1] - sum[y0 * (w + 1) + x1] - sum[y1 * (w + 1) + x0] + sum[y0 * (w + 1) + x0]
            return (s / area).toFloat()
        }
    }

    private class Candidate(val x: Float, val y: Float, val s: Float, val score: Float)

    fun findGrid(pixels: IntArray, width: Int, height: Int, rows: Int, cols: Int): BoardGrid? {
        val factor = max(1, width / TARGET_WIDTH)
        val map = ChromaMap(pixels, width, height, factor)
        var best: Candidate? = null
        val minSize = map.w / (cols * 2f)
        val maxSize = min(map.w / cols.toFloat(), map.h / rows.toFloat())
        var s = minSize
        while (s <= maxSize) {
            val step = max(1f, s / 8)
            var y = 0f
            while (y + rows * s <= map.h) {
                var x = 0f
                while (x + cols * s <= map.w) {
                    val score = gridScore(map, x, y, s, rows, cols)
                    if (best == null || score > best.score) best = Candidate(x, y, s, score)
                    x += step
                }
                y += step
            }
            s *= SIZE_STEP
        }
        var found = best ?: return null
        // Refine around the best coarse grid with 1 px steps and 0.25 % size steps.
        val coarse = found
        val range = max(1f, coarse.s / 8)
        var size = coarse.s * 0.98f
        while (size <= coarse.s * 1.02f) {
            var y = coarse.y - range
            while (y <= coarse.y + range) {
                var x = coarse.x - range
                while (x <= coarse.x + range) {
                    if (x >= 0 && y >= 0 && x + cols * size <= map.w + 0.5f && y + rows * size <= map.h + 0.5f) {
                        val score = gridScore(map, x, y, size, rows, cols)
                        if (score > found.score) found = Candidate(x, y, size, score)
                    }
                    x += 1f
                }
                y += 1f
            }
            size *= 1.0025f
        }
        if (found.score < MIN_GRID_SCORE) return null
        val f = map.factor.toFloat()
        var grid = BoardGrid(found.x * f, found.y * f, found.s * f, rows, cols)
        // The chroma score is biased by orb highlights (pale, low chroma at the top of an orb), so
        // fit the grid to the orbs' edges at full resolution; twice, as the windows move with it.
        repeat(2) { grid = refineGrid(pixels, width, height, grid) ?: grid }
        return grid
    }

    /**
     * Fits left, top and cell size to the orb centres measured from their edges: in a window of
     * one cell around each centre, the extent of the columns and rows that hold enough strongly
     * coloured pixels. Highlights sit inside an orb, so they do not move its edges. Least squares
     * over all cells; cells far from the current grid (odd orbs) are ignored.
     */
    internal fun refineGrid(pixels: IntArray, width: Int, height: Int, grid: BoardGrid): BoardGrid? {
        val s = grid.cell
        val half = (s / 2).toInt()
        val xs = ArrayList<Pair<Int, Float>>() // (column, measured centre x)
        val ys = ArrayList<Pair<Int, Float>>()
        val colCount = IntArray(2 * half + 1)
        val rowCount = IntArray(2 * half + 1)
        for (i in 0 until grid.rows * grid.cols) {
            val cx = grid.centerX(i).roundToInt()
            val cy = grid.centerY(i).roundToInt()
            colCount.fill(0)
            rowCount.fill(0)
            for (dy in -half..half) {
                val y = cy + dy
                if (y < 0 || y >= height) continue
                for (dx in -half..half) {
                    val x = cx + dx
                    if (x < 0 || x >= width) continue
                    if (chroma(pixels[y * width + x]) >= EDGE_CHROMA) {
                        colCount[dx + half]++
                        rowCount[dy + half]++
                    }
                }
            }
            val minRun = (s * 0.15f).toInt()
            val (x0, x1) = extent(colCount, minRun) ?: continue
            val (y0, y1) = extent(rowCount, minRun) ?: continue
            val mx = cx - half + (x0 + x1) / 2f
            val my = cy - half + (y0 + y1) / 2f
            if (abs(mx - grid.centerX(i)) < s * 0.2f) xs += (i % grid.cols) to mx
            if (abs(my - grid.centerY(i)) < s * 0.2f) ys += (i / grid.cols) to my
        }
        if (xs.size < grid.cols || ys.size < grid.rows) return null
        // Model: x = left + (c + 0.5) * cell, y = top + (r + 0.5) * cell. Normal equations in (left, top, cell).
        var sxx = 0.0
        var sx = 0.0
        var sxv = 0.0
        var sv = 0.0
        for ((c, v) in xs) {
            val k = c + 0.5
            sxx += k * k
            sx += k
            sxv += k * v
            sv += v
        }
        var syy = 0.0
        var sy = 0.0
        var syv = 0.0
        var svy = 0.0
        for ((r, v) in ys) {
            val k = r + 0.5
            syy += k * k
            sy += k
            syv += k * v
            svy += v
        }
        val nx = xs.size.toDouble()
        val ny = ys.size.toDouble()
        // Eliminate left and top: cell = (Σk·v - Σk·Σv/n) / (Σk² - (Σk)²/n), summed over both axes.
        val num = (sxv - sx * sv / nx) + (syv - sy * svy / ny)
        val den = (sxx - sx * sx / nx) + (syy - sy * sy / ny)
        if (den <= 0) return null
        val cell = num / den
        if (cell < s * 0.9 || cell > s * 1.1) return null
        val left = (sv - cell * sx) / nx
        val top = (svy - cell * sy) / ny
        return BoardGrid(left.toFloat(), top.toFloat(), cell.toFloat(), grid.rows, grid.cols)
    }

    /** First and last index whose count reaches [minRun], or null. */
    private fun extent(counts: IntArray, minRun: Int): Pair<Int, Int>? {
        val first = counts.indexOfFirst { it >= minRun }
        val last = counts.indexOfLast { it >= minRun }
        return if (first < 0 || last <= first) null else first to last
    }

    /**
     * Mean chroma of the cell centres minus mean chroma at the inner cell corners (between four
     * orbs). Outer corners are left out: they often lie on a dark frame, which would reward grids
     * that are slightly too large.
     */
    private fun gridScore(map: ChromaMap, x: Float, y: Float, s: Float, rows: Int, cols: Int): Float {
        var centres = 0f
        for (r in 0 until rows) for (c in 0 until cols) centres += map.mean(x + (c + 0.5f) * s, y + (r + 0.5f) * s, s * 0.5f)
        var corners = 0f
        for (r in 1 until rows) for (c in 1 until cols) corners += map.mean(x + c * s, y + r * s, s * 0.16f)
        return centres / (rows * cols) - corners / ((rows - 1) * (cols - 1))
    }

    // ---- 2. Orbs --------------------------------------------------------------------------------

    /** Returns (orb code or -1, share of the winning attribute among coloured ring pixels). */
    internal fun classify(pixels: IntArray, width: Int, height: Int, cx: Float, cy: Float, cell: Float): Pair<Int, Float> {
        val votes = FloatArray(Orbs.ATTRIBUTES)
        var sampled = 0
        var coloured = 0
        val steps = 72
        var radius = RING_INNER * cell
        val radiusStep = max(1f, cell / 60)
        while (radius <= RING_OUTER * cell) {
            for (k in 0 until steps) {
                val angle = 2 * Math.PI * k / steps
                val x = (cx + radius * cos(angle)).roundToInt()
                val y = (cy + radius * sin(angle)).roundToInt()
                if (x < 0 || y < 0 || x >= width || y >= height) continue
                sampled++
                val attribute = attributeOf(pixels[y * width + x])
                if (attribute >= 0) {
                    coloured++
                    votes[attribute] += 1f
                }
            }
            radius += radiusStep
        }
        if (sampled == 0 || coloured < sampled * MIN_COLOURED_SHARE) return -1 to 0f
        var best = 0
        for (a in votes.indices) if (votes[a] > votes[best]) best = a
        val share = votes[best] / coloured
        return (if (share >= MIN_CONFIDENCE) best else -1) to share
    }

    /** Attribute code (R B G L D H) of a saturated, bright pixel, or -1. */
    internal fun attributeOf(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val v = mx / 255f
        val s = if (mx == 0) 0f else (mx - mn) / mx.toFloat()
        if (s < 0.35f || v < 0.45f) return -1
        val hue = hue(r, g, b, mx, mn)
        return when {
            hue < 22 || hue >= 345 -> 0 // Fire: red, red-orange
            hue < 40 -> -1 // orange: between Fire and Light, ignored
            hue < 72 -> 3 // Light: yellow
            hue < 170 -> 2 // Wood: green
            hue < 255 -> 1 // Water: cyan, blue
            hue < 300 -> 4 // Dark: purple
            else -> 5 // Heart: pink, magenta
        }
    }

    private fun hue(r: Int, g: Int, b: Int, mx: Int, mn: Int): Float {
        val d = (mx - mn).toFloat()
        if (d == 0f) return 0f
        val h = when (mx) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return if (h < 0) h + 360 else h
    }

    private fun chroma(argb: Int): Int {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return max(r, max(g, b)) - min(r, min(g, b))
    }
}
