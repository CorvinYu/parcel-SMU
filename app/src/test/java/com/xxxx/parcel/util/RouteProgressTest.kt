package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「取到一半怎么接着走」（用户 2026-10-01）：
 * 1. 刚取完一件 ⇒ 下一段**从现场那一点出发**，不是又从入口进（引擎支持自定义起点）
 * 2. 同货架的连续取件点**合并成一枚标记**，避免互相盖住
 * 3. 只有**时间窗内**的最近一次取件才能当起点（隔夜再来仍从入口）
 */
class RouteProgressTest {

    private val options = RouteOptions.DEFAULT

    private fun spot(code: String): PickupSpot {
        val parsed = parseCompartmentCode(code)!!
        return locate(parsed, options)!!
    }

    @Test
    fun `以已取点为起点时路线从现场出发`() {
        val b4 = spot("B4-3")
        val fromEntrance = planPickupRoute(listOf("D5-23"), options)
        val fromHere = planPickupRoute(
            rawCodes = listOf("D5-23"),
            options = options,
            startCell = GridCell(b4.row, b4.col),
            startLabel = "已取的 B4-3",
        )

        assertEquals("首段起点名应写成现场那件", "已取的 B4-3", fromHere.legs.first().from)
        val head = fromHere.legs.first().cells.first()
        assertEquals("首段必须从传进来的点出发", b4.row to b4.col, head.row to head.col)
        assertNotEquals(
            "不该再从入口出发",
            fromEntrance.legs.first().cells.first().let { it.row to it.col },
            head.row to head.col,
        )
        assertTrue(
            "从现场接着走应比从入口出发更短：${fromHere.totalTiles} vs ${fromEntrance.totalTiles}",
            fromHere.totalTiles < fromEntrance.totalTiles,
        )
        // 段几何不变量照旧成立
        fromHere.legs.forEach { leg ->
            assertEquals(
                leg.tiles,
                leg.stubFromTiles + (leg.cells.size - 1) * 0.5 + leg.stubToTiles,
                1e-9,
            )
        }
    }

    @Test
    fun `同一货架的连续取件点合并成一组`() {
        val route = planPickupRoute(listOf("S3-2-2628", "S3-3-7606", "D5-23"), options)
        val groups = groupRouteStops(route)
        val pickGroups = groups.filter { it.isPickup }
        val merged = pickGroups.firstOrNull { it.indexes.size >= 2 }
        assertTrue("S3 的两件应合并成一组：${pickGroups.map { it.indexes }}", merged != null)
        assertEquals("合并的应正好是两件", 2, merged!!.indexes.size)
        assertEquals(
            "代表格必须落在通道上（不许平均到货架里）",
            SiteModel.WALK,
            SiteModel.kindAt(merged.cell.row, merged.cell.col),
        )
        assertTrue("不同货架要各自成组", pickGroups.size >= 2)
        // 出站/顺丰各自成组，且组数与「合并后的站数」一致
        assertEquals(
            "分组必须覆盖所有停靠点",
            route.stops.size,
            groups.sumOf { it.indexes.size },
        )
    }

    @Test
    fun `取件记录只有窗口内最近一次才作起点`() {
        val now = 1_000_000_000L
        assertNull("没有记录 ⇒ 从入口进", pickCheckoutOriginCode(emptyList(), now))
        assertEquals("刚取完 ⇒ 用它当起点", "B4-3", pickCheckoutOriginCode(listOf("B4-3" to now - 1_000), now))
        assertNull(
            "超过时间窗（3 小时）⇒ 仍从入口进",
            pickCheckoutOriginCode(listOf("B4-3" to now - CHECKOUT_ORIGIN_WINDOW_MS - 1), now),
        )
        assertNull(
            "时间戳在未来的脏数据不许用",
            pickCheckoutOriginCode(listOf("B4-3" to now + 5_000), now),
        )
        assertEquals(
            "取最新的一条",
            "D5-23",
            pickCheckoutOriginCode(listOf("D5-23" to now - 10, "B4-3" to now - 99_999), now),
        )
    }
}
