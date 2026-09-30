package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排序回归：**引擎的顺序必须等于暴力枚举的最优解**（模型无关的硬断言）。
 *
 * 历史：旧模型（手写 16 排 + 双干线）时期这里断言过「B 排东侧 4 件连成一段、西侧 3 件连成一段」。
 * 换成数据驱动模型后这个预期不再成立 —— 用户 Excel 里最南那条走廊在 col 41（AO 列）
 * 处有 1 格墙，东西两半必须绕到别的走廊才能互通，因此最优解会把离出口最近的那件留到最后。
 * ⇒ 与其把旧几何写死，不如直接钉住「与暴力枚举一致」。
 */
class RouteOrderingRegressionTest {

    private val options = RouteOptions.DEFAULT

    private fun routeOf(codes: List<String>): PickupRoute = planPickupRoute(codes, options)

    @Test
    fun `B排 7 件的顺序等于暴力枚举最优解`() {
        val codes = listOf("B1-1", "B12-1", "B4-1", "B3-1", "B5-1", "B8-1", "B10-1")
        val route = routeOf(codes)
        println("输入: ${codes.joinToString()}")
        println("顺序: ${route.orderedCodes.joinToString()}  总格数=${route.totalTiles}  精确=${route.exact}")
        route.legTiles.forEachIndexed { i, v -> print(if (i == 0) "  入口→$v" else "  →$v") }
        println()

        assertTrue("7 件应走精确解", route.exact)
        assertEquals("引擎顺序必须等于暴力枚举最优", bruteOptimum(codes), route.totalTiles, 1e-6)
        // 输入顺序不能比最优更短（否则说明暴力枚举算错了）
        assertTrue("最优不应劣于输入顺序", route.totalTiles <= orderCost(codes))
    }

    /** 按输入顺序走的代价（同一套距离、同一套 stub 语义）。 */
    private fun orderCost(codes: List<String>): Double {
        var total = 0.0
        routeOf(codes).let { _ -> }
        val spots = codes.mapNotNull { parseCompartmentCode(it)?.let { c -> locate(c, options) } }
        val entrance = TestGates2.entrance()!!
        fun d(a: PickupSpot, b: PickupSpot): Int {
            val bfs = SiteModel.bfs(a.row, a.col)!!
            return SiteModel.distTo(bfs, b.row, b.col)
        }
        fun dFrom(row: Int, col: Int, s: PickupSpot): Int {
            val bfs = SiteModel.bfs(row, col)!!
            return SiteModel.distTo(bfs, s.row, s.col)
        }
        var prev: PickupSpot? = null
        spots.forEach { s ->
            val cells = if (prev == null) dFrom(entrance.row, entrance.col, s) else d(prev!!, s)
            total += cells * SiteModel.CELL_TILES
            if (prev != null) total += prev!!.stubTiles
            total += s.stubTiles
            prev = s
        }
        // 出口：普通闸机
        val gates = TestGates2.normalGates()
        val bfs = SiteModel.bfs(prev!!.row, prev!!.col)!!
        val exitCells = gates.minOf { SiteModel.distTo(bfs, it.row, it.col) }
        total += exitCells * SiteModel.CELL_TILES + prev!!.stubTiles
        return total
    }

    /** 全排列 + 出口固定为普通闸机（这批都是普通件，没有顺丰出库约束）。 */
    private fun bruteOptimum(codes: List<String>): Double {
        val spots = codes.map { c -> locate(parseCompartmentCode(c)!!, options)!! }
        val entrance = TestGates2.entrance()!!
        val gates = TestGates2.normalGates()
        fun d(a: PickupSpot, b: PickupSpot): Int {
            val bfs = SiteModel.bfs(a.row, a.col)!!
            return SiteModel.distTo(bfs, b.row, b.col)
        }
        var best = Double.MAX_VALUE
        val idx = IntArray(spots.size) { it }
        fun cost(order: IntArray): Double {
            var total = 0.0
            var prev: PickupSpot? = null
            for (i in order) {
                val s = spots[i]
                total += if (prev == null) SiteModel.distTo(SiteModel.bfs(entrance.row, entrance.col)!!, s.row, s.col) * SiteModel.CELL_TILES
                else d(prev!!, s) * SiteModel.CELL_TILES
                if (prev != null) total += prev!!.stubTiles
                total += s.stubTiles
                prev = s
            }
            val bfs = SiteModel.bfs(prev!!.row, prev!!.col)!!
            total += gates.minOf { SiteModel.distTo(bfs, it.row, it.col) } * SiteModel.CELL_TILES + prev!!.stubTiles
            return total
        }
        fun permute(k: Int) {
            if (k == spots.size) {
                val c = cost(idx)
                if (c < best) best = c
                return
            }
            for (i in k until spots.size) {
                val t = idx[k]; idx[k] = idx[i]; idx[i] = t
                permute(k + 1)
                val t2 = idx[k]; idx[k] = idx[i]; idx[i] = t2
            }
        }
        permute(0)
        return best
    }
}

/** 测试用闸机/入口索引（与 PickupRouteTest 里同源，独立于生产代码的 private SiteIndex）。 */
private object TestGates2 {
    private fun labelled(): List<Pair<String, IntArray>> {
        val out = ArrayList<Pair<String, IntArray>>()
        for (i in SiteData.rectLabels.indices) {
            val b = i * 4
            out += SiteData.rectLabels[i].trim().uppercase() to intArrayOf(
                SiteData.rectBounds[b], SiteData.rectBounds[b + 1],
                SiteData.rectBounds[b + 2], SiteData.rectBounds[b + 3],
            )
        }
        return out
    }

    /** 闸机**门口**的通道格（带外那一格）—— 与生产代码同规则、各自实现。 */
    private fun mouthsOf(b: IntArray): List<GridCell> = buildList {
        for (r in b[2]..b[3]) for (c in b[0]..b[1]) {
            if (SiteModel.kindAt(r - 1, c) == SiteModel.WALK) add(GridCell(r - 1, c))
            if (SiteModel.kindAt(r + 1, c) == SiteModel.WALK) add(GridCell(r + 1, c))
            if (SiteModel.kindAt(r, c - 1) == SiteModel.WALK) add(GridCell(r, c - 1))
            if (SiteModel.kindAt(r, c + 1) == SiteModel.WALK) add(GridCell(r, c + 1))
        }
    }.distinct()

    fun normalGates(): List<GridCell> =
        labelled().filter { it.first.contains("普通闸机") }.flatMap { mouthsOf(it.second) }.distinct()

    fun entrance(): GridCell? {
        val src = ArrayList<GridCell>()
        var centerRow = 0.0
        var centerCol = 0.0
        var widest = -1
        var i = 0
        while (i < SiteData.entranceSpans.size) {
            val row = SiteData.entranceSpans[i]
            val c0 = SiteData.entranceSpans[i + 1]
            val c1 = SiteData.entranceSpans[i + 2]
            if (c1 - c0 > widest) {
                widest = c1 - c0
                src.clear()
                for (c in c0..c1) src += GridCell(row, c)
                centerRow = row.toDouble()
                centerCol = (c0 + c1 + 1) / 2.0
            }
            i += 3
        }
        if (src.isEmpty()) return null
        return SiteModel.nearestWalkFrom(src, centerRow, centerCol)
    }
}
