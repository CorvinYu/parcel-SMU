package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `走廊带按行区间合并后主区 9 条、纵干 4 条`() {
        // 🔴 2026-10-03 换用用户 10-02 更新版地图后：
        //   ① 走廊不再规整为 4 行高（出现 18~19 / 20~21 / 25 等），必须按「行区间相邻即合并」
        //      ⇒ 主货架区恢复为规整的 9 条（6~9 … 54~57）
        //   ② 新图 Y 区向南扩展（通道到第 69 行）⇒ 多出 2 条 Y 区南部走廊带（列 97~129）
        //   合计 11 条；主货架区的 9 条必须与旧图一一对应，否则「第 N 条」会错位。
        assertEquals("横走廊带总数", 11, VenueGuide.bands.size)
        // 2026-10-03：用户 10-02 更新版地图新增了东侧纵向通道 ⇒ 3 条变 4 条
        assertEquals("纵向干线数", 4, VenueGuide.trunks.size)
        // 主货架区的 9 条：**编号从入口起算**（入口以南的 2 条 Y 区南部带排在最后）
        val mainBands = VenueGuide.bands.filter { it.r0 in 6..57 }
        assertEquals("主货架区应有 9 条走廊带", 9, mainBands.size)
        assertEquals(
            "主区行区间应与旧图一致（从入口往里）",
            listOf(54, 48, 42, 35, 30, 24, 18, 12, 6),
            mainBands.map { it.r0 },
        )
        assertEquals(
            "最南那条被主通道 1 格断开 ⇒ 必须合并回一条，否则「第 N 条」会整体错位",
            1, VenueGuide.bands.count { it.r0 == 54 && it.r1 == 57 },
        )
        assertEquals(
            "🔴 主货架区那条必须是第 1 条（入口就在它上面）——" +
                "否则用户按「第 1 条横走廊」找不到路",
            54, VenueGuide.bands.first().r0,
        )
        assertTrue("最后两条应是入口以南的 Y 区南部带",
            VenueGuide.bands.takeLast(2).all { it.r0 > 59 })
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
    fun `Y 区已有精确格位，提示不再声称只能到通道口`() {
        // 🔴 2026-10-03：用户 10-02 更新后 Y 区被拆成 `Y1-1`…`Y8-7-3` 独立货格 ⇒ 可精确定位。
        //    旧版此测试断言「必须标注只能带到东侧通道口」——那是旧图（Y 区一整块）的行为，已过时。
        val y = spot("Y5-7-1")
        assertFalse("新图 Y 区已细分 ⇒ 不该再标为近似", y.approximate)
        val route = planPickupRoute(listOf("Y5-7-1", "B1-1"), options)
        val leg = route.legs.first { it.to == "Y5-7-1" }
        val notes = VenueGuide.describe(leg, y).filter { it.kind == VenueGuide.HintKind.NOTE }
        assertTrue(
            "不该再出现「只能带到东侧通道口」这类旧精度声明：${notes.map { it.text }}",
            notes.none { it.text.contains("只能带到东侧通道口") },
        )
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
    fun `首页短提示完整列出每一步转向`() {
        val route = planPickupRoute(listOf("B4-3", "Y5-7-1", "J5-21", "A1-1"), options)
        val leg = route.legs.last { it.kind == LegKind.PICK }
        val s = VenueGuide.summarize(leg)
        val moves = VenueGuide.describe(leg).filter { it.kind == VenueGuide.HintKind.MOVE }
        assertTrue("短提示不该为空：$s", s.isNotEmpty())
        // 不再截断（用户 2026-10-01：看不到后半段）
        val parts = s.split(" → ")
        assertEquals("每一步都要出现，不许截断", moves.size, parts.size)
        assertFalse("不许出现省略号", s.contains("→…"))
    }

    @Test
    fun `合并区标签与边界一一对应（地图标注索引的前提）`() {
        // 地图画货架时要靠下标取名字：rectBounds 每 4 个数一个矩形、rectLabels 每个矩形一个。
        // 一旦这条不成立，货架标注就会整体错位（用户 2026-10-01 截图反馈「标注几乎全乱」）。
        assertEquals(
            "rectLabels 数量 × 4 必须等于 rectBounds 长度",
            SiteData.rectLabels.size * 4,
            SiteData.rectBounds.size,
        )
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

    @Test
    fun `全取完的地址不进路线（标记已取件后地图要同步）`() {
        // 全取完 ⇒ 调用方传空列表 ⇒ 必须返回 null（该地址从路线与图上消失）
        assertNull("已取完的地址不得再参与规划", routeAnchorCode(emptyList()))
        // 还没取 ⇒ 用它自己的货格号
        assertEquals("D5-23", routeAnchorCode(listOf("" to "D5-23")))
        assertEquals("D8-6", routeAnchorCode(listOf("D8-6" to "12345678")))
        // 取件码本身就是货格号时的兜底仍然有效（嵌在中文长句里的不解析 —— 由短信解析器负责）
        assertEquals("B4-3", routeAnchorCode(listOf("" to "B4-3")))
        assertNull("嵌在中文句子里的货格号不在这里碰运气", routeAnchorCode(listOf("" to "请用B4-3到人工货架取包裹")))
        // 两条里只留下未取件的那条 ⇒ 取锚点结果不变，但已取件那条不会再产生额外站点
        assertEquals("S3-2-2628", routeAnchorCode(listOf("S3-2-2628" to "2628")))
    }
}
