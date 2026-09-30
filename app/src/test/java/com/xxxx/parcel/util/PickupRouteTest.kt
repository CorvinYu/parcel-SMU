package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取件路线引擎测试（2026-09-29 换代为**数据驱动**模型后重写）。
 *
 * 场地可走格来自用户 Excel 的填充色（[SiteData]，3362 格）；这里验证：
 * 1. 网格与 Excel 一致、通道结构（3 纵干 + ≥8 横走廊）
 * 2. 定位：普通排 / S 顺丰按格位横向 / J 柜列按格位纵深 / Y 整块（如实标注）
 * 3. 投影**不穿墙**（J 柜列三面是墙、朝南开）
 * 4. 距离满足度量公理（对称、d(a,a)=0、三角不等式）
 * 5. **最优性**：Held–Karp 与**独立暴力枚举**逐例比对（含顺丰出库点所有合法插入位置）
 * 6. **顺丰规则**：出库 ≠ 出站（用户 2026-09-29）
 * 7. 逐段距离之和 == 总距离；快递柜/无法定位的分流
 */
class PickupRouteTest {

    private val options = RouteOptions.DEFAULT

    private fun spot(code: String): PickupSpot {
        val parsed = parseCompartmentCode(code)
        assertNotNull("$code 应能解析", parsed)
        val located = locate(parsed!!, options)
        assertNotNull("$code 应能定位", located)
        return located!!
    }

    private fun dist(a: PickupSpot, b: PickupSpot): Int {
        val bfs = SiteModel.bfs(a.row, a.col)
        assertNotNull(bfs)
        return SiteModel.distTo(bfs!!, b.row, b.col)
    }

    // ------------------------------------------------------------ 网格与结构

    @Test
    fun `网格可走格与 Excel 通道填充色一致`() {
        // Excel 里 theme3（通道）共 3362 格；生成 SiteData 时逐格照抄
        assertEquals(3362, SiteModel.walkCellCount())
    }

    @Test
    fun `通道结构识别出 3 条纵向干线与至少 8 条横向走廊`() {
        val structure = SiteModel.analyze()
        val longTrunks = structure.trunks.filter { it.r1 - it.r0 >= 30 }
        assertEquals("应识别出 3 条纵向干线", 3, longTrunks.size)
        val west = longTrunks.firstOrNull { it.c0 <= 6 && it.c1 >= 10 }
        assertNotNull("西侧（靠闸机）那条纵向干线必须在模型里", west)
        val longCorridors = structure.corridors.filter { it.c1 - it.c0 >= 30 }
        assertTrue("横向走廊应 ≥8 条，实际 ${longCorridors.size}", longCorridors.size >= 8)
    }

    // ------------------------------------------------------------ 定位

    @Test
    fun `普通排按合并区中心定位并投影到通道`() {
        val b1 = spot("B1-1")
        assertTrue(b1.zone == PickupZone.MAIN)
        assertTrue("B1 应在西侧（lat<0），实际 ${b1.lat}", b1.lat < 0)
        assertEquals(SiteModel.WALK, SiteModel.kindAt(b1.row, b1.col))
        assertFalse(b1.approximate)
    }

    @Test
    fun `S 顺丰按格位横向展开（左端为 1）`() {
        val s1a = spot("S1-1")
        val s1b = spot("S1-10")
        assertTrue("S1-1 应在 S1-10 之西：${s1a.lat} vs ${s1b.lat}", s1a.lat < s1b.lat)
        assertEquals(1.5, s1a.lat, 1e-9)
        assertEquals(4.5, s1b.lat, 1e-9)
        val s3a = spot("S3-2-2628")
        val s3b = spot("S3-8")
        assertTrue("s3 也是左端为 1", s3a.lat < s3b.lat)
    }

    @Test
    fun `S 区同货架不同格不会投到同一格`() {
        // 回归：投影决胜基准若用「合并区中心」，同一货架的所有格都会投到同一格 ⇒ 段距恒为 0（踩过）
        val a = spot("S3-2-2628")
        val b = spot("S3-3-7606")
        assertFalse(
            "S3-2 与 S3-3 投到了同一格 (${a.row},${a.col}) ⇒ 段距恒为 0",
            a.row == b.row && a.col == b.col,
        )
        val s1a = spot("S1-1")
        val s1b = spot("S1-10")
        assertTrue("S1 首尾格应投到不同列：${s1a.col} vs ${s1b.col}", s1a.col < s1b.col)
    }

    @Test
    fun `J 柜列按格位纵向展开且纵深计入走位`() {
        val j5c1 = spot("J5-1")
        val j5c21 = spot("J5-21")
        assertTrue("格子 1 在靠通道的外端（浅），21 更靠里", j5c1.depth < j5c21.depth)
        assertTrue("越往里区内走位越大", j5c21.stubTiles > j5c1.stubTiles)
        assertTrue("J5-21 的区内走位应 > 0", j5c21.stubTiles > 0.5)
        assertTrue("格位必须落在柜列合并区纵深内", j5c21.depth <= 25.0 + 1e-6)
    }

    @Test
    fun `J 柜列投影不穿墙`() {
        // J 区三面是墙（X/AH 列 + 北墙），朝南开向最北那条走廊 ⇒ 只能投到南侧走廊
        for (code in listOf("J1-1", "J5-21", "J6-1")) {
            val j = spot(code)
            assertTrue("$code 投到 (${j.row},${j.col}) 穿墙了？", j.row >= 12)
            assertTrue("$code 的列应在 J 区范围内", j.col in 24..35)
        }
    }

    @Test
    fun `Y 区在精确版里是一整块只定位到最近通道点`() {
        val y = spot("Y5-7-1")
        assertTrue("必须如实标注为近似", y.approximate)
        assertEquals(SiteModel.WALK, SiteModel.kindAt(y.row, y.col))
    }

    @Test
    fun `越界与未知的货格号定位失败`() {
        assertNull("排字母不在场地里", locate(parseCompartmentCode("Z1-1")!!, options))
        assertNull("主货架只有 1~12", locate(parseCompartmentCode("D13-1")!!, options))
        assertNull("J 只有 6 条柜列", locate(parseCompartmentCode("J7-1")!!, options))
        assertNull("S 只有 3 个货架", locate(parseCompartmentCode("S4-1")!!, options))
        assertNull("Y 只有 8 个", locate(parseCompartmentCode("Y9-1")!!, options))
    }

    // ------------------------------------------------------------ 度量公理

    @Test
    fun `距离满足度量公理`() {
        val codes = listOf("B1-1", "B12-1", "D8-6", "F12-32", "Q1-3", "N5-1", "K3-2", "A4-1", "Y5-7-1")
        val spots = codes.map { spot(it) }
        for (a in spots) {
            assertEquals("d(a,a)=0", 0, dist(a, a))
            for (b in spots) {
                assertEquals("对称性", dist(a, b), dist(b, a))
                for (c in spots) {
                    assertTrue("三角不等式", dist(a, c) <= dist(a, b) + dist(b, c))
                }
            }
        }
    }

    // ------------------------------------------------------------ 逐段一致

    @Test
    fun `逐段距离之和等于总距离`() {
        val route = planPickupRoute(listOf("B1-1", "D8-6", "F12-32", "S3-2-2628", "J5-21", "Q1-3"), options)
        assertEquals(route.stops.size, route.legTiles.size)
        assertEquals(route.totalTiles, route.legTiles.sum(), 1e-9)
        assertTrue("应该是精确解", route.exact)
    }

    @Test
    fun `快递柜与无法定位的码被分流`() {
        val route = planPickupRoute(listOf("D8-6", "54018314", "XYZ", "Q1-3"), options)
        assertEquals(listOf("54018314"), route.lockerCodes)
        assertEquals(listOf("XYZ"), route.unresolved)
        assertEquals(setOf("D8-6", "Q1-3"), route.orderedCodes.map { it.toString() }.toSet())
    }

    // ------------------------------------------------------------ 顺丰规则

    @Test
    fun `纯普通件不绕顺丰出库且终点是普通闸机`() {
        val route = planPickupRoute(listOf("B1-1", "D8-6"), options)
        assertFalse("不该出现顺丰出库停靠点", route.hasSfCheckout)
        assertEquals(RouteExit.NORMAL_GATE, route.exit)
        assertTrue(route.stops.last() is RouteStop.Exit)
        val exitCell = route.exitCell!!
        assertTrue("终点应落在 7个普通闸机带（行 22~57），实际 ${exitCell.row}", exitCell.row in 22..57)
    }

    @Test
    fun `纯顺丰件必须先出库再出站（出库机不能出站）`() {
        val route = planPickupRoute(listOf("S1-10", "S3-2-2628"), options)
        assertTrue("必须有顺丰出库停靠点", route.hasSfCheckout)
        assertEquals(RouteExit.SF_EXIT, route.exit)
        val sfCell = route.sfCheckoutCell!!
        assertTrue("出库点应在「顺丰专用闸机」带（行 17~21），实际 ${sfCell.row}", sfCell.row in 17..21)
        val exitCell = route.exitCell!!
        assertTrue("出站点应在「顺丰和无快递出口」（行 12~16），实际 ${exitCell.row}", exitCell.row in 12..16)
        assertEquals("出库点必须排在所有 S 件之后", 2, route.sfCheckoutAfter)
        assertEquals(4, route.stops.size)
        assertTrue(route.stops[0] is RouteStop.Pickup)
        assertTrue(route.stops[1] is RouteStop.Pickup)
        assertTrue(route.stops[2] is RouteStop.SfCheckout)
        assertTrue(route.stops[3] is RouteStop.Exit)
    }

    @Test
    fun `混合件顺丰出库在所有 S 件之后且最后从普通闸机出`() {
        val route = planPickupRoute(listOf("S3-2-2628", "B1-1", "D8-6", "J5-21"), options)
        assertTrue(route.hasSfCheckout)
        assertEquals(RouteExit.NORMAL_GATE, route.exit)
        val stops = route.stops
        val sfIdx = stops.indexOfFirst { it is RouteStop.SfCheckout }
        assertTrue("出库点必须在最后一个 S 件之后", sfIdx > 0)
        val picksBefore = stops.take(sfIdx).filterIsInstance<RouteStop.Pickup>()
        assertEquals("出库前应恰好取完 1 件 S", 1, picksBefore.count { it.code.zone == PickupZone.SF })
        val picksAfter = stops.drop(sfIdx).filterIsInstance<RouteStop.Pickup>()
        assertTrue("出库后不该还有 S 件", picksAfter.none { it.code.zone == PickupZone.SF })
        assertTrue(stops.last() is RouteStop.Exit)
        assertTrue("终点落在普通闸机带", route.exitCell!!.row in 22..57)
    }

    // ------------------------------------------------------------ 大件数（启发式）自洽

    @Test
    fun `大件数 20 件走启发式但结构自洽`() {
        // 用户 2026-09-30 的真实现场：一次取 53 件 ⇒ 必须走启发式（2^53 不可能精确）
        val pool = SiteData.rectLabels
            .map { it.trim().uppercase() }
            .filter { Regex("^[A-R]\\d{1,2}$").matches(it) }
            .distinct()
        assertTrue("标签池应够大，实际 ${pool.size}", pool.size >= 20)
        val codes = pool.take(18).map { "$it-1" } + listOf("S3-2-2628", "J5-21")
        val route = planPickupRoute(codes, options)

        assertFalse(">16 件必须如实标为非精确", route.exact)
        assertEquals("件数不该丢", codes.size, route.orderedCodes.size)
        assertEquals("件不该重复", codes.size, route.orderedCodes.map { it.toString() }.toSet().size)
        assertEquals("停靠点数 == 段数", route.stops.size, route.legTiles.size)
        assertEquals("逐段和 == 总距离", route.totalTiles, route.legTiles.sum(), 1e-9)
        assertTrue("总距离应为正", route.totalTiles > 0)
        // 含顺丰件 ⇒ 必须有出库停靠点，且出库后不再有 S 件
        assertTrue("应出现顺丰出库停靠点", route.hasSfCheckout)
        val sfIdx = route.stops.indexOfFirst { it is RouteStop.SfCheckout }
        assertTrue(
            "出库后不该还有 S 件",
            route.stops.drop(sfIdx).filterIsInstance<RouteStop.Pickup>().none { it.code.zone == PickupZone.SF },
        )
        assertTrue("终点是出站", route.stops.last() is RouteStop.Exit)
        println("20 件启发式：共 ${route.totalTiles} 格，${route.stops.size} 站，sfAfter=${route.sfCheckoutAfter}")
    }

    // ------------------------------------------------------------ 顺丰「已出库」 + 不压闸机带

    @Test
    fun `顺丰已出库时路线不再绕出库机`() {
        val codes = listOf("S1-1-1", "D8-6")
        val notYet = planPickupRoute(codes, options)
        assertTrue("没标记「已出库」时应保留顺丰出库节点", notYet.hasSfCheckout)

        val done = planPickupRoute(codes, options, sfCheckedOut = true)
        assertFalse("标记已出库后不该再有顺丰出库节点", done.hasSfCheckout)
        assertTrue("路线仍以出站结尾", done.stops.last() is RouteStop.Exit)
        assertEquals("停靠点 = 件数 + 出站", codes.size + 1, done.stops.size)
        assertEquals("逐段和 == 总距离", done.totalTiles, done.legTiles.sum(), 1e-9)
    }

    @Test
    fun `只有顺丰件且已出库时终点是顺丰侧出站口`() {
        val r = planPickupRoute(listOf("S1-1-1"), options, sfCheckedOut = true)
        assertFalse(r.hasSfCheckout)
        assertEquals(RouteExit.SF_EXIT, r.exit)
        assertTrue("停在出站口", r.stops.last() is RouteStop.Exit)
    }

    /** 回归：闸机带（列 3~5）不可通行 ⇒ 路线**不会压在闸机上**（用户 2026-10-01 两次反馈）。 */
    @Test
    fun `路线不进入闸机带`() {
        val samples = listOf(
            listOf("S1-1-1"),
            listOf("D8-6"),
            listOf("S1-1-1", "D8-6"),
            listOf("J5-21", "S2-1-5728", "D8-6"),
        )
        for (codes in samples) {
            for (done in listOf(false, true)) {
                val r = planPickupRoute(codes, options, sfCheckedOut = done)
                r.legs.forEach { leg ->
                    assertTrue(
                        "闸机带（col 3~5）不可通行，路线不该出现这些格：codes=$codes done=$done leg=${leg.kind}",
                        leg.cells.none { it.col in 3..5 },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ 最优性（与独立暴力枚举比对）

    /**
     * 独立暴力枚举：枚举取件顺序**全排列** × 顺丰出库点的**所有合法插入位置**（必须在最后一个 S 件之后），
     * 用「显式停靠序列」累加距离。
     *
     * 与引擎共用同一份最短路原语（网格 BFS），但**搜索方式完全不同** —— 这正是交叉验证的意义。
     */
    private fun bruteForce(codes: List<String>): Double {
        val spots = codes.map { spot(it) }
        val n = spots.size
        val hasSf = spots.any { it.zone == PickupZone.SF }
        val hasNormal = spots.any { it.zone != PickupZone.SF }
        val entrance = TestGates.entrance()!!
        val sfCell = TestGates.sfCheckoutCell()!!
        val normalGates = TestGates.normalGates()
        val sfExitGates = TestGates.sfExitGates()
        val cell = SiteModel.CELL_TILES
        val unreachable = Int.MAX_VALUE / 4

        fun d(aRow: Int, aCol: Int, bRow: Int, bCol: Int): Int {
            val bfs = SiteModel.bfs(aRow, aCol) ?: return unreachable
            val v = SiteModel.distTo(bfs, bRow, bCol)
            return if (v < 0) unreachable else v
        }

        fun minTo(row: Int, col: Int, cells: List<GridCell>): Int =
            cells.minOf { d(row, col, it.row, it.col) }

        var best = Double.MAX_VALUE
        val idx = IntArray(n) { it }

        fun evaluate() {
            var lastSf = -1
            for (i in 0 until n) if (spots[idx[i]].zone == PickupZone.SF) lastSf = i
            val positions = if (hasSf) (lastSf + 1..n).toList() else listOf(-1)
            for (pos in positions) {
                var cost = 0.0
                var prevRow = -1
                var prevCol = -1
                var prevStub = 0.0
                var atSfNode = false
                var atEntrance = true
                var i = 0
                while (i <= n) {
                    if (hasSf && i == pos) {
                        // 离开上一站：若是取件点，要先从货架/柜列里走出来
                        if (!atEntrance && !atSfNode) cost += prevStub
                        cost += if (atEntrance) d(entrance.row, entrance.col, sfCell.row, sfCell.col) * cell
                        else minTo(prevRow, prevCol, listOf(sfCell)) * cell
                        atSfNode = true
                        atEntrance = false
                    }
                    if (i == n) break
                    val s = spots[idx[i]]
                    if (!atEntrance && !atSfNode) cost += prevStub        // 离开上一个取件点
                    val dCells = when {
                        atEntrance -> d(entrance.row, entrance.col, s.row, s.col)
                        atSfNode -> d(sfCell.row, sfCell.col, s.row, s.col)
                        else -> d(prevRow, prevCol, s.row, s.col)
                    }
                    cost += dCells * cell + s.stubTiles                    // 到达（走进货架/柜列）
                    prevRow = s.row
                    prevCol = s.col
                    prevStub = s.stubTiles
                    atSfNode = false
                    atEntrance = false
                    i++
                }
                cost += when {
                    hasNormal -> {
                        var tail = if (!atEntrance && !atSfNode) prevStub else 0.0
                        tail += (if (atSfNode) minTo(sfCell.row, sfCell.col, normalGates)
                        else minTo(prevRow, prevCol, normalGates)) * cell
                        tail
                    }
                    else -> if (atSfNode) sfExitGates.minOf { d(sfCell.row, sfCell.col, it.row, it.col) } * cell
                    else Double.MAX_VALUE
                }
                if (cost < best) best = cost
            }
        }

        fun permute(k: Int) {
            if (k == n) {
                evaluate(); return
            }
            for (i in k until n) {
                val t = idx[k]; idx[k] = idx[i]; idx[i] = t
                permute(k + 1)
                val t2 = idx[k]; idx[k] = idx[i]; idx[i] = t2
            }
        }
        permute(0)
        return best
    }

    @Test
    fun `Held_Karp 与暴力枚举逐例一致（含顺丰出库约束）`() {
        val pool = listOf(
            "B1-1", "B12-1", "D8-6", "F12-32", "Q1-3", "N5-1", "K3-2", "A4-1", "Y5-7-1",
            "S1-1", "S3-8", "S3-2-2628", "J5-21",
        )
        var seed = 20260929L
        fun nextInt(bound: Int): Int {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            return ((seed ushr 33).toInt() and 0x7fffffff) % bound
        }
        var checked = 0
        for (trial in 0 until 40) {
            val size = 1 + trial % 5     // n = 1..5，全排列可承受
            val chosen = LinkedHashSet<String>()
            while (chosen.size < size) chosen.add(pool[nextInt(pool.size)])
            val codes = chosen.toList()
            val route = planPickupRoute(codes, options)
            val brute = bruteForce(codes)
            assertEquals("$codes 引擎与暴力枚举应一致", brute, route.totalTiles, 1e-6)
            checked++
        }
        assertEquals(40, checked)
    }
}

/**
 * 测试用场地索引：从标签反推三处闸机带与入口。
 *
 * 刻意**不复用**生产代码里的 private `SiteIndex`，顺便验证「标签 → 闸机带」的识别规则：
 * 「7个普通闸机」= 普通出口；顺丰标签里含「专用」的是**出库机**，另一处（顺丰和无快递出口）是**出站口**。
 */
private object TestGates {

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

    /**
     * 闸机**门口**的通道格（站在闸机前那一格，在闸机带**外面**）。
     * 与生产代码 `PickupRoute.gateMouths` **同一规则、各自实现**（闸机带本身不可通行）。
     */
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

    fun sfExitGates(): List<GridCell> = labelled()
        .filter { it.first.contains("顺丰") && it.first.contains("出口") }
        .flatMap { mouthsOf(it.second) }
        .distinct()

    fun sfCheckoutCell(): GridCell? {
        val sf = labelled().filter { it.first.contains("顺丰") && it.first.contains("专用") }
        val cells = sf.flatMap { mouthsOf(it.second) }.distinct()
        if (cells.isEmpty()) return null
        val cr = (sf.minOf { it.second[2] } + sf.maxOf { it.second[3] } + 1) / 2.0
        val cc = (sf.minOf { it.second[0] } + sf.maxOf { it.second[1] } + 1) / 2.0
        return cells.minByOrNull {
            val dr = it.row - cr
            val dc = it.col - cc
            dr * dr + dc * dc
        }
    }

    /** 入口：用户用单独颜色填的入口闸机（`W59:AB59`）→ 投影到最近通道格。 */
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
