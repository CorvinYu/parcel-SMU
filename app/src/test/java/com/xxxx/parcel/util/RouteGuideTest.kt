package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「怎么走到下一个取货点」的测试（2026-10-01 新增）。
 *
 * 钉住两件事：
 * 1. **几何**：每段的格序列都在通道上、每步相邻；`tiles == stubFrom + (cells-1)×0.5 + stubTo`
 *    （stub 是瓷砖、网格是单元格 —— 历史上「stub 只算 1 次」就是这条不成立暴露的）
 * 2. **文案**：走廊带合并后正好 9 条、方向压缩自洽、金样例措辞、精度如实标注（Y 区/普通货架/J 柜列）
 */
class RouteGuideTest {

    private val options = RouteOptions.DEFAULT

    private fun spot(code: String): PickupSpot {
        val parsed = parseCompartmentCode(code)
        assertNotNull("$code 应能解析", parsed)
        val located = locate(parsed!!, options)
        assertNotNull("$code 应能定位", located)
        return located!!
    }

    private val picks = listOf("B4-3", "D5-23", "J5-21", "S3-2-2628", "Y5-7-1", "A1-1", "R12-5")

    // ---------------------------------------------------------------- 几何

    @Test
    fun `每段的瓷砖账与格序列自洽（stub 分账恒等式）`() {
        val route = planPickupRoute(picks, options)
        assertEquals("停靠点数 == 段数", route.stops.size, route.legs.size)
        route.legs.forEach { leg ->
            assertTrue("${leg.from}→${leg.to} 应有格序列", leg.cells.isNotEmpty())
            assertEquals(
                "${leg.from}→${leg.to} 的 stub 分账不成立",
                leg.tiles,
                leg.stubFromTiles + (leg.cells.size - 1) * 0.5 + leg.stubToTiles,
                1e-9,
            )
        }
        assertEquals("逐段和 == 总距离", route.totalTiles, route.legs.sumOf { it.tiles }, 1e-9)
        assertEquals("legTiles 仍由 legs 派生（兼容旧调用方）", route.legTiles, route.legs.map { it.tiles })
    }

    @Test
    fun `每段格序列都在通道格上且每步相邻`() {
        val route = planPickupRoute(picks, options)
        route.legs.forEach { leg ->
            leg.cells.forEachIndexed { i, c ->
                assertNotEquals(
                    "${leg.from}→${leg.to} 第 $i 点 (${c.row},${c.col}) 不在通道上",
                    SiteModel.NONE, SiteModel.kindAt(c.row, c.col),
                )
                if (i > 0) {
                    val p = leg.cells[i - 1]
                    assertEquals(
                        "${leg.from}→${leg.to} 第 $i 点与前点不相邻 ⇒ 会斜穿或跳格",
                        1, kotlin.math.abs(c.row - p.row) + kotlin.math.abs(c.col - p.col),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 结构命名

    @Test
    fun `走廊带按行区间合并后正好 9 条、纵干 3 条`() {
        assertEquals("横走廊带数", 9, VenueGuide.bands.size)
        assertEquals("纵向干线数", 3, VenueGuide.trunks.size)
        assertEquals(
            "最南那条被主通道 1 格断开 ⇒ 必须合并回一条，否则「第 N 条」会整体错位",
            1, VenueGuide.bands.count { it.r0 == 54 && it.r1 == 57 },
        )
        assertTrue("第 1 条应最靠门口", VenueGuide.bands.first().r0 > VenueGuide.bands.last().r0)
        assertEquals("主通道必须被认出来", 1, VenueGuide.trunks.count { it.name == "主通道" })
        assertTrue("地标应能推出货架字母", VenueGuide.bands.any { it.landmark.contains("/") })
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun `方向压缩自洽：步数之和等于该段格数、相邻段方向不同`() {
        val route = planPickupRoute(picks, options)
        route.legs.forEach { leg ->
            val moves = VenueGuide.describe(leg).filter { it.kind == VenueGuide.HintKind.MOVE }
            assertEquals(
                "${leg.from}→${leg.to} 提示步数之和应等于该段格数",
                (leg.cells.size - 1) * 0.5,
                moves.sumOf { it.tiles },
                1e-9,
            )
            for (i in 1 until moves.size) {
                assertNotEquals("相邻两段方向不该相同", moves[i - 1].dir, moves[i].dir)
            }
        }
    }

    @Test
    fun `入口到第一件的提示说出走廊编号与方向（金样例）`() {
        val route = planPickupRoute(listOf("B4-3", "D5-23"), options)
        val first = route.legs.first()
        assertEquals(LegKind.ENTRANCE, first.kind)
        val moves = VenueGuide.describe(first).filter { it.kind == VenueGuide.HintKind.MOVE }
        assertTrue("应有转向提示", moves.isNotEmpty())
        assertTrue("第一条应说清是哪条走廊：${moves.first().text}", moves.first().text.contains("第1条横走廊"))
        assertEquals(VenueGuide.Dir.NORTH, moves.first().dir)
        val nextMoves = VenueGuide.describe(route.legs[1])
        assertTrue(
            "跨站段应提到主通道：${nextMoves.map { it.text }}",
            nextMoves.any { it.text.contains("主通道") },
        )
    }

    @Test
    fun `简洁版不带走廊名、详细版带走廊名`() {
        val route = planPickupRoute(listOf("B4-3"), options)
        val moves = VenueGuide.describe(route.legs.first()).filter { it.kind == VenueGuide.HintKind.MOVE }
        assertTrue(moves.any { it.text.contains("走廊") })
        assertTrue("简洁版不该带走廊名：${moves.map { it.brief }}", moves.none { it.brief.contains("走廊") })
    }

    @Test
    fun `J 柜列末条提示说明走进柜列多少格`() {
        val j5 = spot("J5-21")
        val route = planPickupRoute(listOf("S3-2-2628", "J5-21"), options)
        val leg = route.legs.last { it.to == "J5-21" }
        val hints = VenueGuide.describe(leg, j5)
        val stub = hints.firstOrNull { it.kind == VenueGuide.HintKind.STUB_IN }
        assertNotNull("J 件必须有「走进柜列」提示", stub)
        assertTrue("文案应说明是柜列：${stub!!.text}", stub.text.contains("走进柜列"))
        assertEquals("stub 步数应与定位结果一致", j5.stubTiles, stub.tiles, 1e-9)
    }

    @Test
    fun `Y 区的提示如实标注只能带到东侧通道口`() {
        val y = spot("Y5-7-1")
        val route = planPickupRoute(listOf("Y5-7-1", "B1-1"), options)
        val leg = route.legs.first { it.to == "Y5-7-1" }
        val notes = VenueGuide.describe(leg, y).filter { it.kind == VenueGuide.HintKind.NOTE }
        assertTrue("必须如实标注 Y 区精度：${notes.map { it.text }}", notes.any { it.text.contains("只能带到东侧通道口") })
    }

    @Test
    fun `普通货架提示如实说明格位未细分`() {
        val d5 = spot("D5-23")
        val route = planPickupRoute(listOf("D5-23", "B1-1"), options)
        val leg = route.legs.first { it.to == "D5-23" }
        val notes = VenueGuide.describe(leg, d5).filter { it.kind == VenueGuide.HintKind.NOTE }
        assertTrue("必须如实标注格位精度：${notes.map { it.text }}", notes.any { it.text.contains("未实测") })
    }

    @Test
    fun `顺丰出库段排在最后一个 S 件之后且出站在最后`() {
        val route = planPickupRoute(listOf("S3-2-2628", "S1-1", "D5-23"), options)
        val sfLegIdx = route.legs.indexOfFirst { it.kind == LegKind.SF_CHECKOUT }
        assertTrue("必须有顺丰出库段", sfLegIdx >= 0)
        val lastSfPick = route.legs.indexOfLast { it.kind == LegKind.PICK && it.to.startsWith("S") }
        assertTrue("出库必须在所有 S 件之后：出库段 $sfLegIdx vs 最后一个 S 件 $lastSfPick", sfLegIdx > lastSfPick)
        assertEquals(RouteExit.NORMAL_GATE, route.exit)
        assertEquals(LegKind.EXIT, route.legs.last().kind)
        assertTrue("出站段应有格序列", route.legs.last().cells.isNotEmpty())
        assertTrue("出站文案应说明是顺丰出库之后", VenueGuide.describe(route.legs.last()).any { it.text.contains("出站") })
    }

    @Test
    fun `纯顺丰件：末段是出站段且终点在顺丰侧出口`() {
        val route = planPickupRoute(listOf("S1-1", "S2-3"), options)
        assertEquals(RouteExit.SF_EXIT, route.exit)
        assertEquals(LegKind.EXIT, route.legs.last().kind)
        assertTrue(
            "出站点应在「顺丰和无快递出口」（行 12~16），实际 ${route.exitCell!!.row}",
            route.exitCell!!.row in 12..16,
        )
        assertTrue("末段应有格序列", route.legs.last().cells.isNotEmpty())
    }

    @Test
    fun `首页短提示最多三个转向`() {
        val route = planPickupRoute(listOf("B4-3", "Y5-7-1", "J5-21", "A1-1"), options)
        val s = VenueGuide.summarize(route.legs.last { it.kind == LegKind.PICK })
        assertTrue("短提示不该为空：$s", s.isNotEmpty())
        assertTrue("最多三个转向（用 → 连接）：$s", s.split("→").size <= 4)
    }

    @Test
    fun `空路线与只有快递柜时不抛异常`() {
        val empty = planPickupRoute(emptyList(), options)
        assertTrue(empty.legs.isEmpty())

        val lockerOnly = planPickupRoute(listOf("54018314", "69824579"), options)
        assertTrue("纯数字码是快递柜，不进人工货架路线", lockerOnly.legs.isEmpty())
        assertEquals(2, lockerOnly.lockerCodes.size)

        val blank = RouteLeg("入口闸机", "X", LegKind.ENTRANCE, emptyList(), 0.0, 0.0, 0.0)
        assertTrue("空段不许编指引", VenueGuide.describe(blank).isEmpty())
        assertEquals("", VenueGuide.summarize(blank))
    }
}
