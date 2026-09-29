package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 取件路径引擎的验证。
 *
 * 两条最关键的测试：
 * 1. **用暴力枚举所有排列**作为参照，验证 Held–Karp 子集 DP 求出的确实是最优解；
 * 2. **用走行图的树结构独立推导理论最优值**（不依赖 DP），两者必须相等。
 *
 * 场地数据全部来自用户 2026-09-29 现场踩点图，这里的断言逐条对着该图写。
 */
class PickupRouteTest {

    private val layout = SiteLayout.default()

    // ===== 货格号解析 =====

    @Test
    fun `解析标准货格号 D5-23`() {
        val code = parseCompartmentCode("D5-23")
        assertNotNull(code)
        assertEquals('D', code!!.rowLetter)
        assertEquals(5, code.shelfNumber)
        assertEquals(23, code.cellNumber)
        assertEquals(PickupZone.MAIN, code.zone)
    }

    @Test
    fun `解析三段式编号（顺丰与大件）`() {
        val sf = parseCompartmentCode("S3-2-2628")!!
        assertEquals('S', sf.rowLetter)
        assertEquals(3, sf.shelfNumber)
        assertEquals(2, sf.cellNumber)
        assertEquals(2628, sf.subNumber)
        assertEquals(PickupZone.SF, sf.zone)

        val bulk = parseCompartmentCode("Y5-7-1")!!
        assertEquals(7, bulk.cellNumber)
        assertEquals(1, bulk.subNumber)
        assertEquals(PickupZone.BULK, bulk.zone)

        val y8 = parseCompartmentCode("Y8-1-3")!!
        assertEquals(8, y8.shelfNumber)
        assertEquals(1, y8.cellNumber)
        assertEquals(3, y8.subNumber)
    }

    @Test
    fun `解析容忍大小写与全角横线`() {
        val a = parseCompartmentCode("d5-23")!!
        assertEquals('D', a.rowLetter)
        assertEquals(23, a.cellNumber)

        val b = parseCompartmentCode("D5－23")!!
        assertEquals(23, b.cellNumber)

        val c = parseCompartmentCode(" D 5 - 23 ")!!
        assertEquals(5, c.shelfNumber)
        assertEquals(23, c.cellNumber)

        val d = parseCompartmentCode("D5—23")!!
        assertEquals(23, d.cellNumber)
    }

    @Test
    fun `只写到货架号也应解析成功`() {
        val code = parseCompartmentCode("A11")!!
        assertEquals('A', code.rowLetter)
        assertEquals(11, code.shelfNumber)
        assertNull(code.cellNumber)
    }

    @Test
    fun `有歧义的写法拒绝解析而不是猜`() {
        assertNull(parseCompartmentCode("D523"))
        assertNull(parseCompartmentCode("5-23"))
        assertNull(parseCompartmentCode("D"))
        assertNull(parseCompartmentCode(""))
        assertNull(parseCompartmentCode("D5-"))
        assertNull(parseCompartmentCode("12"))
        assertNull(parseCompartmentCode("abc"))
    }

    // ===== 场地结构（对着踩点图逐条断言） =====

    @Test
    fun `排字母是16排且没有 I 和 J`() {
        val letters = layout.rowLetters
        assertEquals(16, letters.size)
        assertTrue("不应包含 I", 'I' !in letters)
        assertTrue("不应包含 J（J 是独立柜列区）", 'J' !in letters)
        // 由入口向里：A…R，且 R 在最里
        assertEquals('A', letters.first())
        assertEquals('R', letters.last())
        assertEquals(listOf('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R'), letters)
    }

    @Test
    fun `每两排背靠背共用一条横向通道`() {
        // A/B 用通道 0，C/D 用通道 1，…，Q/R 用通道 7；J/S 区挂在最里侧通道 8
        assertEquals(0, layout.aisleOf(0))   // A
        assertEquals(0, layout.aisleOf(1))   // B
        assertEquals(1, layout.aisleOf(2))   // C
        assertEquals(1, layout.aisleOf(3))   // D
        assertEquals(7, layout.aisleOf(14))  // Q
        assertEquals(7, layout.aisleOf(15))  // R
        assertEquals(8, layout.innermostAisle)

        // 同一通道的南北两侧：偶数下标（A、C、E…）在南，奇数（B、D、F…）在北
        assertEquals(AisleSide.SOUTH, layout.aisleSideOf(0))   // A 最靠入口
        assertEquals(AisleSide.NORTH, layout.aisleSideOf(1))   // B
        assertEquals(AisleSide.SOUTH, layout.aisleSideOf(2))   // C
        assertEquals(AisleSide.NORTH, layout.aisleSideOf(3))   // D
    }

    @Test
    fun `货架到主通道的横向格数（西4东8）`() {
        assertEquals(12, lateralTilesFor(1, layout))  // 西侧最远：3 + 3×3
        assertEquals(3, lateralTilesFor(4, layout))   // 紧邻通道：半个通道 + 半个货架
        assertEquals(3, lateralTilesFor(5, layout))   // 紧邻通道
        assertEquals(24, lateralTilesFor(12, layout)) // 东侧最远：3 + 3×7
        assertNull(lateralTilesFor(0, layout))
        assertNull(lateralTilesFor(13, layout))
    }

    // ===== 定位 =====

    @Test
    fun `定位：A1 在入口第一排最西侧`() {
        val pos = locate(parseCompartmentCode("A1")!!, layout)!!
        assertEquals(PickupZone.MAIN, pos.zone)
        assertEquals(0, pos.aisle)
        assertEquals(0, pos.depthTiles)
        assertEquals(12, pos.lateralTiles)
        assertEquals(SpineSide.WEST, pos.spineSide)
        assertEquals(AisleSide.SOUTH, pos.aisleSide)
    }

    @Test
    fun `定位：R12 在最里侧那一排的最东端`() {
        val pos = locate(parseCompartmentCode("R12")!!, layout)!!
        assertEquals(7, pos.aisle)
        assertEquals(21, pos.depthTiles)      // 7 × 3
        assertEquals(24, pos.lateralTiles)
        assertEquals(SpineSide.EAST, pos.spineSide)
        assertEquals(AisleSide.NORTH, pos.aisleSide)
    }

    @Test
    fun `定位：J 柜列沿列向里递增`() {
        val j5c1 = locate(parseCompartmentCode("J5-1")!!, layout)!!
        val j5c21 = locate(parseCompartmentCode("J5-21")!!, layout)!!
        assertEquals(PickupZone.J_CABINET, j5c1.zone)
        assertEquals(SpineSide.WEST, j5c1.spineSide)
        assertEquals(j5c1.lateralTiles, j5c21.lateralTiles)   // 同一条柜列，横向相同
        assertEquals(20, j5c21.depthTiles - j5c1.depthTiles)  // 第 21 格比第 1 格深 20 格
        // j6 最靠主通道（横向最小），j1 最靠西（横向最大）
        val j1 = locate(parseCompartmentCode("J1-1")!!, layout)!!
        val j6 = locate(parseCompartmentCode("J6-1")!!, layout)!!
        assertTrue(j1.lateralTiles > j6.lateralTiles)
        assertNull(locate(parseCompartmentCode("J7-1")!!, layout))  // 只有 6 条柜列
    }

    @Test
    fun `定位：S 顺丰区格子沿横向递增`() {
        val s1 = locate(parseCompartmentCode("S1-1")!!, layout)!!
        val s1Last = locate(parseCompartmentCode("S1-10")!!, layout)!!
        assertEquals(PickupZone.SF, s1.zone)
        assertEquals(SpineSide.EAST, s1.spineSide)
        assertEquals(9, s1Last.lateralTiles - s1.lateralTiles)
        // 实测样例 S3-2-2628：s3 货架、第 2 格
        val s3 = locate(parseCompartmentCode("S3-2-2628")!!, layout)!!
        assertEquals(SpineSide.EAST, s3.spineSide)
        assertEquals(3 + 2 - 1, s3.lateralTiles)
        assertNull(locate(parseCompartmentCode("S4-1")!!, layout))   // 只有 3 个货架
    }

    @Test
    fun `定位：Y 区反向编号的货架（右端为 1）`() {
        // y2 是反向编号：y2-1 在东端、y2-4 在西端
        val y2c1 = locate(parseCompartmentCode("Y2-1")!!, layout)!!
        val y2c4 = locate(parseCompartmentCode("Y2-4")!!, layout)!!
        assertEquals(PickupZone.BULK, y2c1.zone)
        assertTrue("y2-1 应比 y2-4 更靠东（右端为 1）", y2c1.lateralTiles > y2c4.lateralTiles)

        // y1 是正常编号：y1-1 在西端
        val y1c1 = locate(parseCompartmentCode("Y1-1")!!, layout)!!
        val y1c9 = locate(parseCompartmentCode("Y1-9")!!, layout)!!
        assertTrue(y1c9.lateralTiles > y1c1.lateralTiles)

        assertNull(locate(parseCompartmentCode("Y9-1")!!, layout))   // 只有 y1~y8
    }

    @Test
    fun `定位：Y8 是三段式，行决定深度、子位决定横向`() {
        val a = locate(parseCompartmentCode("Y8-1-1")!!, layout)!!
        val b = locate(parseCompartmentCode("Y8-1-3")!!, layout)!!
        val deep = locate(parseCompartmentCode("Y8-8-1")!!, layout)!!
        // 同一行：子位 1→3 横向递增
        assertEquals(2, b.lateralTiles - a.lateralTiles)
        // 行 1 比行 8 更深（原图 y8-1 在上、y8-8 在下）
        assertTrue("y8 第 1 行应比第 8 行更深", a.depthTiles > deep.depthTiles)
    }

    @Test
    fun `M 是普通排而不是大物区（用户更正）`() {
        val code = parseCompartmentCode("M5-5")!!
        assertEquals(PickupZone.MAIN, code.zone)
        val pos = locate(code, layout)
        assertNotNull("M5-5 应当能定位：大物是 Y 不是 M", pos)
        assertEquals(5, pos!!.code.shelfNumber)
    }

    @Test
    fun `未知字母与越界货架号仍返回 null`() {
        assertNull(locate(parseCompartmentCode("Z1-1")!!, layout))    // Z 不在排序列里
        assertNull(locate(parseCompartmentCode("D13-1")!!, layout))   // 主货架 1~12
    }

    @Test
    fun `真实短信样例全部可以定位（含 J S Y）`() {
        listOf(
            "B4-18", "D8-6", "F12-32", "F7-24", "Q12-25", "D3-24", "M5-5",
            "E5-5", "E9-9", "F2-5", "F11-12", "Q11-27",
            "J5-21", "S3-2-2628", "Y5-7-1",
        ).forEach { raw ->
            val code = parseCompartmentCode(raw)
            assertNotNull("$raw 应能解析", code)
            assertNotNull("$raw 应能定位", locate(code!!, layout))
        }
    }

    @Test
    fun `混合输入时各归其位`() {
        val route = planPickupRoute(
            listOf("D8-6", "J5-21", "S3-2-2628", "Y5-7-1", "54018314", "乱写"),
            layout,
        )
        assertEquals(4, route.resolvedCount)                                   // 普通排 + J + S + Y
        assertEquals(listOf("54018314"), route.lockerCodes)
        assertEquals(listOf("乱写"), route.unresolved)
        assertEquals(4, route.zoneCounts().values.sum())
        assertEquals(1, route.zoneCounts()[PickupZone.J_CABINET])
        assertEquals(1, route.zoneCounts()[PickupZone.SF])
        assertEquals(1, route.zoneCounts()[PickupZone.BULK])
    }

    // ===== 距离度量性质 =====

    @Test
    fun `距离对称且满足三角不等式`() {
        val samples = listOf("A1", "A12", "C4", "C5", "F9", "L1", "J3-5", "S1-3", "Y5-7")
            .map { locate(parseCompartmentCode(it)!!, layout)!! }

        for (a in samples) for (b in samples) {
            assertEquals(walkTiles(a, b, layout), walkTiles(b, a, layout))
            assertEquals(0, walkTiles(a, a, layout))
        }
        for (a in samples) for (b in samples) for (c in samples) {
            assertTrue(
                "三角不等式失败: ${a.code} ${b.code} ${c.code}",
                walkTiles(a, c, layout) <= walkTiles(a, b, layout) + walkTiles(b, c, layout),
            )
        }
    }

    @Test
    fun `单件包裹的总步数等于入口进出往返`() {
        val route = planPickupRoute(listOf("C5-10"), layout, returnToEntrance = true)
        val pos = locate(parseCompartmentCode("C5-10")!!, layout)!!
        assertEquals(entranceToTiles(pos, layout) + exitFromTiles(pos, layout), route.totalTiles)
        assertEquals(2, route.legTiles.size)
    }

    // ===== 精确性：与暴力枚举对照 =====

    @Test
    fun `DP 结果与暴力枚举一致（折返入口）`() {
        runExactnessComparison(returnToEntrance = true)
    }

    @Test
    fun `DP 结果与暴力枚举一致（不折返，敞开路径）`() {
        runExactnessComparison(returnToEntrance = false)
    }

    private fun runExactnessComparison(returnToEntrance: Boolean) {
        val random = Random(20260929) // 固定种子 ⇒ 可复现
        val rowLetters = layout.rowLetters
        for (size in 1..6) {
            repeat(30) {
                val codes = (0 until size).map {
                    when (random.nextInt(8)) {
                        0 -> "J${random.nextInt(1, 7)}-${random.nextInt(1, 22)}"
                        1 -> "S${random.nextInt(1, 4)}-${random.nextInt(1, 11)}"
                        2 -> "Y${random.nextInt(1, 8)}-${random.nextInt(1, 10)}"
                        3 -> "Y8-${random.nextInt(1, 9)}-${random.nextInt(1, 4)}"
                        else -> {
                            val row = rowLetters[random.nextInt(rowLetters.size)]
                            val shelf = random.nextInt(1, layout.shelvesPerRow + 1)
                            "$row$shelf-${random.nextInt(1, 40)}"
                        }
                    }
                }
                val positions = codes.map { locate(parseCompartmentCode(it)!!, layout)!! }
                val brute = bruteForceMin(positions, layout, returnToEntrance)
                val route = planPickupRoute(codes, layout, returnToEntrance)
                assertEquals(
                    "件数=$size 组合=$codes 折返=$returnToEntrance 时 DP 不是最优",
                    brute,
                    route.totalTiles,
                )
            }
        }
    }

    // ===== 通道路径规则 =====

    @Test
    fun `同一条通道同一侧可直接沿通道走`() {
        val a = locate(parseCompartmentCode("A1-1")!!, layout)!!   // 西侧，lateral 4
        val b = locate(parseCompartmentCode("A2-1")!!, layout)!!   // 西侧，lateral 3
        assertEquals(3, walkTiles(a, b, layout))   // 12 - 9
        assertEquals(3, walkTiles(b, a, layout))
    }

    @Test
    fun `同一条通道异侧必须绕经主通道`() {
        val a = locate(parseCompartmentCode("A4-1")!!, layout)!!   // 西，lateral 1
        val b = locate(parseCompartmentCode("A5-1")!!, layout)!!   // 东，lateral 1
        assertEquals(6, walkTiles(a, b, layout))   // 3 + 3
        assertEquals(6, walkTiles(b, a, layout))
    }

    @Test
    fun `背靠背的两排之间过不去，必须各自回主通道`() {
        // A 排（通道 0 南侧）与 B 排（通道 0 北侧）在同一横向位置
        val a4 = locate(parseCompartmentCode("A4-1")!!, layout)!!
        val b4 = locate(parseCompartmentCode("B4-1")!!, layout)!!
        assertEquals(0, a4.depthTiles)
        assertEquals(0, b4.depthTiles)
        assertEquals(AisleSide.SOUTH, a4.aisleSide)
        assertEquals(AisleSide.NORTH, b4.aisleSide)
        // 用户 2026-09-29 现场确认：背靠背挡死 ⇒ 各自回主通道 = 横向 1 + 1
        assertEquals(6, walkTiles(a4, b4, layout))   // 3 + 3
        assertEquals(6, walkTiles(b4, a4, layout))

        // 横向也要走一段时：横向「之和」（不是差）
        val b1 = locate(parseCompartmentCode("B1-1")!!, layout)!!   // 横向 4
        assertEquals(3 + 12, walkTiles(a4, b1, layout))

        // 不同通道同样回主通道：横向 + 通道间距 + 横向
        val c4 = locate(parseCompartmentCode("C4-1")!!, layout)!!
        assertEquals(3 + layout.aisleSpacingTiles + 3, walkTiles(a4, c4, layout))
    }

    @Test
    fun `终点是西侧大门而不是南门`() {
        val m1 = locate(parseCompartmentCode("M1-1")!!, layout)!!
        val a1 = locate(parseCompartmentCode("A1-1")!!, layout)!!
        val r1 = locate(parseCompartmentCode("R1-1")!!, layout)!!
        // 西门在中间深度附近 ⇒ 靠中间深度的排离出口最近
        assertTrue(
            "靠近西门的排应比最南/最北的排近",
            exitFromTiles(m1, layout) < exitFromTiles(a1, layout) &&
                exitFromTiles(m1, layout) < exitFromTiles(r1, layout),
        )
        // 口径：横向回主通道 + |深度差| + 出口横向
        val m4 = locate(parseCompartmentCode("M4-1")!!, layout)!!   // 紧邻主通道 ⇒ lateral 1
        val expected = 3 + kotlin.math.abs(m4.depthTiles - layout.exitDepthTiles) + layout.exitLateralTiles
        assertEquals(expected, exitFromTiles(m4, layout))
    }

    @Test
    fun `最深的是 R 排与 J 柜列（比入口第一排远得多）`() {
        val a1 = locate(parseCompartmentCode("A1-1")!!, layout)!!
        val r1 = locate(parseCompartmentCode("R1-1")!!, layout)!!
        val j1 = locate(parseCompartmentCode("J1-1")!!, layout)!!
        assertTrue(entranceToTiles(a1, layout) < entranceToTiles(r1, layout))
        assertTrue(entranceToTiles(r1, layout) < entranceToTiles(j1, layout))
    }

    @Test
    fun `最优值可用树结构独立推导（南门进、西门出）`() {
        // 独立验算：不依赖 DP，直接用走行图的树结构推导理论最优值。
        // 0.1.9 起终点是**西门**（不是回到南门），所以这里验两条：
        //   ① 南门进 → 西门出（含所有目标）：2×Steiner − dist(南门, 西门)
        //   ② 敞开路径（终点任意）：2×Steiner − 最深目标深度
        val codes = listOf("A1-1", "C4-1", "E5-1", "G9-1")
        val positions = codes.map { locate(parseCompartmentCode(it)!!, layout)!! }
        assertEquals(
            "样本必须落在互不相同的通道上",
            positions.size,
            positions.map { it.aisle }.distinct().size,
        )

        // 最小子树（含西门那条支路）：主通道从入口伸到「最深目标与西门中更深的那个」
        val deepest = maxOf(positions.maxOf { it.depthTiles }, layout.exitDepthTiles)
        val steinerWithExit = layout.doorToSpineTiles +
            deepest +
            positions.sumOf { it.lateralTiles } +
            layout.exitLateralTiles

        val doorToExit = layout.doorToSpineTiles + layout.exitDepthTiles + layout.exitLateralTiles
        val toExit = planPickupRoute(codes, layout, returnToEntrance = true).totalTiles
        assertEquals("南门进→西门出 应等于 2×Steiner − dist(南门,西门)",
            2 * steinerWithExit - doorToExit, toExit)

        // 敞开路径不经过西门 ⇒ 子树里不能算西门那条支路
        val steinerOpen = layout.doorToSpineTiles +
            positions.maxOf { it.depthTiles } +
            positions.sumOf { it.lateralTiles }
        val maxDepth = positions.maxOf { entranceToTiles(it, layout) }
        val openPath = planPickupRoute(codes, layout, returnToEntrance = false).totalTiles
        assertEquals("敞开路径最优应等于 2×Steiner − 最深目标深度", 2 * steinerOpen - maxDepth, openPath)
    }

    @Test
    fun `不折返时最优解把最远的一件排在最后`() {
        val codes = listOf("A1-1", "A12-1", "G1-1")
        val positions = codes.map { locate(parseCompartmentCode(it)!!, layout)!! }
        val farthest = positions.maxByOrNull { entranceToTiles(it, layout) }!!
        val route = planPickupRoute(codes, layout, returnToEntrance = false)
        assertEquals(
            "敞开路径应在「入口出发最远」的那件收尾",
            farthest.code.toString(),
            route.orderedCodes.last().toString(),
        )
        // ⚠️ 用实测尺度（一个货架 3 格）算出来的反直觉结论，值得留个记号：
        // A12-1 横向 24 格 ⇒ 距入口 28；G1-1 深度 9 + 横向 12 = 25 ⇒ **最远的不再是排最深的那件**。
        assertEquals('A', route.orderedCodes.last().rowLetter)
    }

    // ===== 异常输入 =====

    @Test
    fun `无法定位的取件码被如实报告且不影响其余件`() {
        val route = planPickupRoute(
            listOf("D5-23", "J9-15", "乱写的", "Z9-9", "A1-1"),
            layout,
            returnToEntrance = true,
        )
        assertEquals(2, route.resolvedCount)
        assertEquals(listOf("J9-15", "乱写的", "Z9-9"), route.unresolved)
        assertTrue(route.totalTiles > 0)
    }

    @Test
    fun `只有特殊区取件码时同样能规划`() {
        val route = planPickupRoute(listOf("J1-1", "S2-2"), layout)
        assertEquals(2, route.resolvedCount)
        assertTrue(route.totalTiles > 0)
        assertEquals(2, route.zoneCounts().size)
    }

    @Test
    fun `空输入返回空路线`() {
        val route = planPickupRoute(emptyList(), layout)
        assertEquals(0, route.totalTiles)
        assertTrue(route.orderedCodes.isEmpty())
        assertTrue(route.unresolved.isEmpty())
    }

    @Test
    fun `同一货架取多件时它们之间步数为零`() {
        val route = planPickupRoute(listOf("B5-1", "B5-2"), layout, returnToEntrance = true)
        assertEquals(2, route.resolvedCount)
        val legs = route.legTiles
        assertEquals(3, legs.size)
        assertEquals(0, legs[1])
    }

    @Test
    fun `件数很多时退化为启发式且仍给出可行解`() {
        val many = (1..20).map { "A${(it % 12) + 1}-$it" }
        val route = planPickupRoute(many, layout, returnToEntrance = true)
        assertEquals(20, route.resolvedCount)
        assertTrue("超过 $MAX_EXACT_ITEMS 件应标记为非精确", !route.exact)
        assertTrue(route.totalTiles > 0)
    }

    // ===== 参照实现：暴力枚举 =====

    private fun bruteForceMin(
        positions: List<SitePosition>,
        layout: SiteLayout,
        returnToEntrance: Boolean,
    ): Int {
        var best = Int.MAX_VALUE
        for (perm in permutations(positions.indices.toList())) {
            var sum = entranceToTiles(positions[perm[0]], layout)
            for (i in 0 until perm.size - 1) {
                sum += walkTiles(positions[perm[i]], positions[perm[i + 1]], layout)
            }
            if (returnToEntrance) sum += exitFromTiles(positions[perm.last()], layout)
            if (sum < best) best = sum
        }
        return best
    }

    private fun <T> permutations(items: List<T>): Sequence<List<T>> = sequence {
        if (items.size <= 1) {
            yield(items)
        } else {
            for (i in items.indices) {
                val rest = items.toMutableList().also { it.removeAt(i) }
                for (tail in permutations(rest)) {
                    yield(listOf(items[i]) + tail)
                }
            }
        }
    }
}
