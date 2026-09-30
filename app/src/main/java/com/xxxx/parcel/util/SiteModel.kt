package com.xxxx.parcel.util

/** 一个网格格（Excel 行号 / 列号）。 */
data class GridCell(val row: Int, val col: Int)

/**
 * 场地网格模型（纯 Kotlin，无 Android 依赖，可在 JVM 单测里跑）。
 *
 * 数据来源：[SiteData]（由用户 Excel 的**填充色**自动生成）
 * - 可走格 = 通道（`theme3`，3362 格）＋ 闸机带（可通行，只用于出行）
 * - **障碍** = 墙（`theme1`）＋ 所有货架合并区 —— 投影/连通必须绕开它们，
 *   否则会把 J 柜列（三面是墙、朝南开）投到主通道里去（＝穿墙取件）。
 *
 * 距离单位：1 单元格 = **0.5 瓷砖**；`lat`（横向，主通道中心=0，西负东正）、
 * `depth`（纵深，入口在南、越大越往里）均以**瓷砖**为单位。
 */
internal object SiteModel {

    const val NONE = 0
    const val WALK = 1
    const val GATE = 2

    /** 1 单元格 = 0.5 瓷砖 */
    const val CELL_TILES = 0.5

    private const val W = SiteData.MAX_COL - SiteData.MIN_COL + 1
    private const val H = SiteData.MAX_ROW - SiteData.MIN_ROW + 1

    /** 每格类型：0 不可走 / 1 通道 / 2 闸机带 */
    private val kind = IntArray(W * H)

    /** 投影障碍：墙 + 货架合并区 */
    private val blocked = IntArray(W * H)

    init {
        var i = 0
        while (i < SiteData.walk.size) {
            val row = SiteData.walk[i]
            val c0 = SiteData.walk[i + 1]
            val c1 = SiteData.walk[i + 2]
            for (c in c0..c1) {
                val k = index(row, c)
                if (k >= 0 && kind[k] == NONE) kind[k] = WALK
            }
            i += 3
        }
        // 🔴 闸机带**一律不算可通行**（用户 2026-10-01 两次反馈「地图上道路压在闸机上」）：
        //    出行停靠点改成带外**紧邻的通道格**（见 `PickupRoute.gateMouths`）⇒ 路线根本不会进闸机带。
        //    （此前先把整条带标成可走、后把「门口那一列」标成可走，都还是会让路线压倒闸机上。）
        i = 0
        while (i < SiteData.wall.size) {
            val row = SiteData.wall[i]
            for (c in SiteData.wall[i + 1]..SiteData.wall[i + 2]) {
                val k = index(row, c)
                if (k >= 0) blocked[k] = 1
            }
            i += 3
        }
        i = 0
        while (i < SiteData.rectBounds.size) {
            val c0 = SiteData.rectBounds[i]
            val c1 = SiteData.rectBounds[i + 1]
            val r0 = SiteData.rectBounds[i + 2]
            val r1 = SiteData.rectBounds[i + 3]
            for (r in r0..r1) for (c in c0..c1) {
                val k = index(r, c)
                if (k >= 0) blocked[k] = 1
            }
            i += 4
        }
    }

    fun index(row: Int, col: Int): Int {
        if (row < SiteData.MIN_ROW || row > SiteData.MAX_ROW) return -1
        if (col < SiteData.MIN_COL || col > SiteData.MAX_COL) return -1
        return (row - SiteData.MIN_ROW) * W + (col - SiteData.MIN_COL)
    }

    fun kindAt(row: Int, col: Int): Int {
        val k = index(row, col)
        return if (k < 0) NONE else kind[k]
    }

    fun isWalkable(row: Int, col: Int): Boolean = kindAt(row, col) != NONE

    fun isBlocked(row: Int, col: Int): Boolean {
        val k = index(row, col)
        return k >= 0 && blocked[k] == 1
    }

    val cellCount: Int get() = W * H

    fun walkCellCount(): Int = kind.count { it == WALK }

    fun gateCellCount(): Int = kind.count { it == GATE }

    /** 列的横向坐标（瓷砖），主通道中心 = 0；这是**格的左边缘**坐标。 */
    fun latOf(col: Int): Double = (col - SiteData.SPINE_COL) / 2.0

    /** 行的纵深坐标（瓷砖），入口在南、越大越往里；这是**格的上边缘**坐标。 */
    fun depthOf(row: Int): Double = (SiteData.BASE_ROW - row) / 2.0

    fun colOf(lat: Double): Double = SiteData.SPINE_COL + 2.0 * lat

    fun rowOf(depth: Double): Double = SiteData.BASE_ROW - 2.0 * depth

    /** 格中心的瓷砖坐标。 */
    fun centerOf(row: Int, col: Int): Pair<Double, Double> =
        (latOf(col) + 0.25) to (depthOf(row) - 0.25)

    // ---------------------------------------------------------------- BFS

    /** 一次 BFS 的结果（dist 单位：单元格；-1 = 不可达）。 */
    class Bfs(val dist: IntArray, val prev: IntArray)

    /** 全网格 BFS（边权恒为 1 单元格 ⇒ BFS 即精确最短路）。 */
    fun bfs(srcRow: Int, srcCol: Int): Bfs? {
        val si = index(srcRow, srcCol)
        if (si < 0 || kind[si] == NONE) return null
        val dist = IntArray(W * H) { -1 }
        val prev = IntArray(W * H) { -1 }
        val queue = IntArray(W * H)
        var head = 0
        var tail = 0
        dist[si] = 0
        queue[tail++] = si
        while (head < tail) {
            val cur = queue[head++]
            val cr = cur / W + SiteData.MIN_ROW
            val cc = cur % W + SiteData.MIN_COL
            val nd = dist[cur] + 1
            for (d in 0 until 4) {
                val nr = if (d == 0) cr - 1 else if (d == 1) cr + 1 else cr
                val nc = if (d == 2) cc - 1 else if (d == 3) cc + 1 else cc
                val ni = index(nr, nc)
                if (ni < 0 || kind[ni] == NONE || dist[ni] != -1) continue
                dist[ni] = nd
                prev[ni] = cur
                queue[tail++] = ni
            }
        }
        return Bfs(dist, prev)
    }

    fun distTo(bfs: Bfs, row: Int, col: Int): Int {
        val k = index(row, col)
        return if (k < 0) -1 else bfs.dist[k]
    }

    /** BFS 回溯：源 → 目标 的完整格序列（每步相邻 ⇒ 画线只会正交、不可能穿货架）。 */
    fun path(bfs: Bfs, row: Int, col: Int): List<GridCell>? {
        var k = index(row, col)
        if (k < 0 || bfs.dist[k] < 0) return null
        val out = ArrayList<GridCell>()
        while (k >= 0) {
            out.add(GridCell(k / W + SiteData.MIN_ROW, k % W + SiteData.MIN_COL))
            k = bfs.prev[k]
        }
        return out.asReversed()
    }

    /**
     * 从一片格子（货架/入口合并区）出发，**绕开墙与货架**找最近的通道格。
     * 同层内以「离该区域中心最近」决胜 ⇒ 结果稳定可复现。
     */
    fun nearestWalkFrom(src: List<GridCell>, centerRow: Double, centerCol: Double): GridCell? {
        val dist = IntArray(W * H) { -1 }
        val queue = IntArray(W * H)
        var head = 0
        var tail = 0
        for (cell in src) {
            val k = index(cell.row, cell.col)
            if (k >= 0 && dist[k] < 0) {
                dist[k] = 0
                queue[tail++] = k
            }
        }
        if (tail == 0) return null
        while (head < tail) {
            val cur = queue[head++]
            val cr = cur / W + SiteData.MIN_ROW
            val cc = cur % W + SiteData.MIN_COL
            val nd = dist[cur] + 1
            for (d in 0 until 4) {
                val nr = if (d == 0) cr - 1 else if (d == 1) cr + 1 else cr
                val nc = if (d == 2) cc - 1 else if (d == 3) cc + 1 else cc
                val ni = index(nr, nc)
                if (ni < 0 || dist[ni] != -1 || blocked[ni] == 1) continue
                dist[ni] = nd
                queue[tail++] = ni
            }
        }
        var best: GridCell? = null
        var bestDist = Int.MAX_VALUE
        var bestTie = Double.MAX_VALUE
        for (k in 0 until W * H) {
            if (kind[k] == NONE || dist[k] < 0) continue
            val r = k / W + SiteData.MIN_ROW
            val c = k % W + SiteData.MIN_COL
            val tie = (r - centerRow) * (r - centerRow) + (c - centerCol) * (c - centerCol)
            if (dist[k] < bestDist || (dist[k] == bestDist && tie < bestTie)) {
                bestDist = dist[k]
                bestTie = tie
                best = GridCell(r, c)
            }
        }
        return best
    }

    // ------------------------------------------------- 通道结构自检（给界面展示/单测）

    /**
     * 把可走格拆成「纵向干线」与「横向走廊」——用于界面自检与单测断言：
     * 期望 3 条纵干（西侧 F~J / 主通道 AI~AN / 东侧 CK~CP）与 ≥8 条横走廊。
     */
    data class Span(val c0: Int, val c1: Int, val r0: Int, val r1: Int)

    class Structure(val trunks: List<Span>, val corridors: List<Span>)

    fun analyze(): Structure {
        val byRun = LinkedHashMap<String, MutableList<Int>>()
        for (c in SiteData.MIN_COL..SiteData.MAX_COL) {
            var runStart = -1
            for (r in SiteData.MIN_ROW..SiteData.MAX_ROW + 1) {
                val w = r <= SiteData.MAX_ROW && kindAt(r, c) == WALK
                if (w && runStart < 0) runStart = r
                else if (!w && runStart >= 0) {
                    if (r - runStart >= 8) byRun.getOrPut("$runStart:${r - 1}") { mutableListOf() }.add(c)
                    runStart = -1
                }
            }
        }
        val trunks = mutableListOf<Span>()
        for ((key, cols0) in byRun) {
            val cols = cols0.sorted()
            var a = cols[0]
            var b = cols[0]
            for (i in 1..cols.size) {
                if (i < cols.size && cols[i] == b + 1) {
                    b = cols[i]
                    continue
                }
                val p = key.split(":")
                trunks.add(Span(a, b, p[0].toInt(), p[1].toInt()))
                if (i < cols.size) {
                    a = cols[i]; b = cols[i]
                }
            }
        }

        val byCols = LinkedHashMap<String, MutableList<Int>>()
        for (r in SiteData.MIN_ROW..SiteData.MAX_ROW) {
            var runStart = -1
            for (c in SiteData.MIN_COL..SiteData.MAX_COL + 1) {
                val w = c <= SiteData.MAX_COL && kindAt(r, c) == WALK
                if (w && runStart < 0) runStart = c
                else if (!w && runStart >= 0) {
                    if (c - runStart >= 8) byCols.getOrPut("$runStart:${c - 1}") { mutableListOf() }.add(r)
                    runStart = -1
                }
            }
        }
        val corridors = mutableListOf<Span>()
        for ((key, rows0) in byCols) {
            val rows = rows0.sorted()
            var a = rows[0]
            var b = rows[0]
            for (i in 1..rows.size) {
                if (i < rows.size && rows[i] == b + 1) {
                    b = rows[i]
                    continue
                }
                val p = key.split(":")
                corridors.add(Span(p[0].toInt(), p[1].toInt(), a, b))
                if (i < rows.size) {
                    a = rows[i]; b = rows[i]
                }
            }
        }
        return Structure(trunks.sortedBy { it.c0 }, corridors.sortedByDescending { it.r0 })
    }
}
