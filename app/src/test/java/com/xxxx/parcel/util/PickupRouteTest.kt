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
 * 最关键的一条：**用暴力枚举所有排列**作为参照，验证 Held–Karp 子集 DP 求出的
 * 确实是最优解（而不是「看起来合理」）。这条测试过了，顺序优化才算真的可信。
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
    }

    @Test
    fun `解析容忍大小写与全角横线`() {
        val a = parseCompartmentCode("d5-23")!!
        assertEquals('D', a.rowLetter)
        assertEquals(23, a.cellNumber)

        // 全角横线
        val b = parseCompartmentCode("D5－23")!!
        assertEquals(23, b.cellNumber)

        // 空格
        val c = parseCompartmentCode(" D 5 - 23 ")!!
        assertEquals(5, c.shelfNumber)
        assertEquals(23, c.cellNumber)

        // 长横线
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
        // D523 既可读作 D-52-3 也可读作 D-5-23，必须拒绝
        assertNull(parseCompartmentCode("D523"))
        assertNull(parseCompartmentCode("5-23"))
        assertNull(parseCompartmentCode("D"))
        assertNull(parseCompartmentCode(""))
        assertNull(parseCompartmentCode("D5-"))
        assertNull(parseCompartmentCode("12"))
        assertNull(parseCompartmentCode("abc"))
    }

    // ===== 场地坐标 =====

    @Test
    fun `货架到主通道的横向格数（左4右8）`() {
        assertEquals(4, lateralTilesFor(1, layout))   // 左侧最远
        assertEquals(1, lateralTilesFor(4, layout))   // 紧邻通道
        assertEquals(1, lateralTilesFor(5, layout))   // 紧邻通道
        assertEquals(8, lateralTilesFor(12, layout))  // 右侧最远
        assertNull(lateralTilesFor(0, layout))
        assertNull(lateralTilesFor(13, layout))
    }

    @Test
    fun `定位：A1 在入口第一排最左侧`() {
        val loc = locate(parseCompartmentCode("A1")!!, layout)!!
        assertEquals(0, loc.rowIndex)
        assertEquals(4, loc.lateralTiles)
        assertTrue(loc.onLeft)
    }

    @Test
    fun `定位：跳过 J 之后 L 的下标是 10`() {
        // A B C D E F G H I K L ⇒ L 的下标是 10（J 被跳过）
        val loc = locate(parseCompartmentCode("L12")!!, layout)!!
        assertEquals(10, loc.rowIndex)
        assertEquals(8, loc.lateralTiles)
        assertTrue(!loc.onLeft)
    }

    @Test
    fun `特殊区与未知排返回 null`() {
        assertNull(locate(parseCompartmentCode("J3-15")!!, layout))  // J 是特殊区
        assertNull(locate(parseCompartmentCode("S6-02")!!, layout))  // S 是特殊区
        assertNull(locate(parseCompartmentCode("M1-01")!!, layout))  // M 是大件仓库
        assertNull(locate(parseCompartmentCode("Z1-1")!!, layout))   // Z 不在排序列里
        assertNull(locate(parseCompartmentCode("D13-1")!!, layout))  // 货架号越界
    }

    // ===== 距离度量性质 =====

    @Test
    fun `距离对称且满足三角不等式`() {
        val samples = listOf("A1", "A12", "C4", "C5", "F9", "L1")
            .map { locate(parseCompartmentCode(it)!!, layout)!! }

        for (a in samples) for (b in samples) {
            assertEquals(walkTiles(a, b, layout), walkTiles(b, a, layout))
            assertEquals(0, walkTiles(a, a, layout))
        }
        for (a in samples) for (b in samples) for (c in samples) {
            assertTrue(
                "三角不等式失败: ${a.code} ${b.code} ${c.code}",
                walkTiles(a, c, layout) <= walkTiles(a, b, layout) + walkTiles(b, c, layout)
            )
        }
    }

    @Test
    fun `单件包裹的总步数等于入口进出往返`() {
        val route = planPickupRoute(listOf("C5-10"), layout, returnToEntrance = true)
        val loc = locate(parseCompartmentCode("C5-10")!!, layout)!!
        assertEquals(entranceToTiles(loc, layout) + exitFromTiles(loc, layout), route.totalTiles)
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
        val random = Random(20261001) // 固定种子 ⇒ 可复现
        val rowLetters = layout.rowLetters
        for (size in 1..6) {
            repeat(30) {
                val codes = (0 until size).map {
                    val row = rowLetters[random.nextInt(rowLetters.size)]
                    val shelf = random.nextInt(1, layout.shelvesPerRow + 1)
                    val cell = random.nextInt(1, 90)
                    "$row$shelf-$cell"
                }
                val locations = codes.map { locate(parseCompartmentCode(it)!!, layout)!! }
                val brute = bruteForceMin(locations, layout, returnToEntrance)
                val route = planPickupRoute(codes, layout, returnToEntrance)
                assertEquals(
                    "件数=$size 组合=$codes 折返=$returnToEntrance 时 DP 不是最优",
                    brute,
                    route.totalTiles
                )
            }
        }
    }

    @Test
    fun `同一排同侧两件可直接沿排走，不必绕回主通道`() {
        val a = locate(parseCompartmentCode("A1-1")!!, layout)!!   // 左侧，lateral 4
        val b = locate(parseCompartmentCode("A2-1")!!, layout)!!   // 左侧，lateral 3
        assertEquals(1, walkTiles(a, b, layout))
        assertEquals(1, walkTiles(b, a, layout))
    }

    @Test
    fun `同一排异侧两件必须绕经主通道`() {
        val a = locate(parseCompartmentCode("A4-1")!!, layout)!!   // 左，lateral 1
        val b = locate(parseCompartmentCode("A5-1")!!, layout)!!   // 右，lateral 1
        assertEquals(2, walkTiles(a, b, layout))
        assertEquals(2, walkTiles(b, a, layout))
    }

    @Test
    fun `折返最优值等于两倍Steiner子树权重，敞开路径再减去最深目标深度`() {
        // 独立验算：不依赖 DP，而用走行图的树结构直接推导理论最优值。
        // 前提：样本里没有「同排同侧」的点对，此时走行图退化为树，公式才成立。
        val codes = listOf("A1-1", "A12-1", "C4-1", "C5-1", "F9-1")
        val locations = codes.map { locate(parseCompartmentCode(it)!!, layout)!! }
        assertTrue(
            "样本必须不含同排同侧点对",
            locations.groupBy { it.rowIndex }.values.all { row ->
                row.count { it.onLeft } <= 1 && row.count { !it.onLeft } <= 1
            }
        )

        val maxRow = locations.maxOf { it.rowIndex }
        val steiner = layout.corridorOffsetTiles +
            layout.rowSpacingTiles * maxRow +
            (0..maxRow).sumOf { row ->
                val inRow = locations.filter { it.rowIndex == row }
                val left = inRow.filter { it.onLeft }.maxOfOrNull { it.lateralTiles } ?: 0
                val right = inRow.filter { !it.onLeft }.maxOfOrNull { it.lateralTiles } ?: 0
                left + right
            }

        val roundTrip = planPickupRoute(codes, layout, returnToEntrance = true).totalTiles
        assertEquals("折返最优应等于 2×Steiner", 2 * steiner, roundTrip)

        val maxDepth = locations.maxOf { entranceToTiles(it, layout) }
        val openPath = planPickupRoute(codes, layout, returnToEntrance = false).totalTiles
        assertEquals("敞开路径最优应等于 2×Steiner − 最深目标深度", 2 * steiner - maxDepth, openPath)
    }

    @Test
    fun `不折返时最优解把最远的一件排在最后`() {
        val codes = listOf("A1-1", "A12-1", "F1-1")
        val route = planPickupRoute(codes, layout, returnToEntrance = false)
        // F 排离入口最深（4 + 5*1 + 4 = 13 格），敞开路径应在它这里收尾
        assertEquals('F', route.orderedCodes.last().rowLetter)
    }

    // ===== 异常输入 =====

    @Test
    fun `无法定位的货格号被如实报告且不影响其余件`() {
        val route = planPickupRoute(
            listOf("D5-23", "J3-15", "乱写的", "Z9-9", "A1-1"),
            layout,
            returnToEntrance = true
        )
        assertEquals(2, route.resolvedCount)
        assertEquals(listOf("J3-15", "乱写的", "Z9-9"), route.unresolved)
        assertTrue(route.totalTiles > 0)
    }

    @Test
    fun `全部无法定位时返回空路线而不是崩溃`() {
        val route = planPickupRoute(listOf("J1-1", "M2-2"), layout)
        assertEquals(0, route.resolvedCount)
        assertEquals(0, route.totalTiles)
        assertEquals(listOf("J1-1", "M2-2"), route.unresolved)
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
        // 入口→B5、B5→B5(0)、B5→入口
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
        locations: List<ShelfLocation>,
        layout: SiteLayout,
        returnToEntrance: Boolean,
    ): Int {
        var best = Int.MAX_VALUE
        for (perm in permutations(locations.indices.toList())) {
            var sum = entranceToTiles(locations[perm[0]], layout)
            for (i in 0 until perm.size - 1) {
                sum += walkTiles(locations[perm[i]], locations[perm[i + 1]], layout)
            }
            if (returnToEntrance) sum += exitFromTiles(locations[perm.last()], layout)
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
