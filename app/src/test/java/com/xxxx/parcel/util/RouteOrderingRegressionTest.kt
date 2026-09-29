package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 复现用户 2026-09-29 反馈的排序问题：
 *
 * 同一条排（B 排）上既有东侧货架（5~12）又有西侧货架（1~4）时，
 * 正确的走法应当是**先在一侧沿通道拿走，再回主通道换到另一侧**，
 * 而不是「东侧拿一个 → 跨到西侧 → 再跨回东侧」来回横穿。
 *
 * 这个测试同时打印实际顺序，便于对照。
 */
class RouteOrderingRegressionTest {

    private val layout = SiteLayout.default()

    private fun order(codes: List<String>): List<String> {
        val route = planPickupRoute(codes, layout, returnToEntrance = true)
        println("输入: ${codes.joinToString()}")
        println("顺序: ${route.orderedCodes.joinToString()}  总格数=${route.totalTiles}  精确=${route.exact}")
        route.legTiles.forEachIndexed { i, v -> print(if (i == 0) "  入口→${v}" else "  →${v}") }
        println()
        return route.orderedCodes.map { it.toString() }
    }

    @Test
    fun `B排东西两侧应分组拿，不要来回横穿`() {
        val codes = listOf("B1-1", "B12-1", "B4-1", "B3-1", "B5-1", "B8-1", "B10-1")
        val seq = order(codes)

        val east = listOf("B12-1", "B10-1", "B8-1", "B5-1")
        val west = listOf("B4-1", "B3-1", "B1-1")

        val eastIdx = east.map { seq.indexOf(it) }
        val westIdx = west.map { seq.indexOf(it) }
        println("东侧位置=$eastIdx 西侧位置=$westIdx")

        // 东侧四件应当连成一段（内部不夹西侧），西侧三件同理
        val eastSpan = eastIdx.max() - eastIdx.min()
        val westSpan = westIdx.max() - westIdx.min()
        assertEquals("东侧 4 件之间不该夹进西侧的件", 3, eastSpan)
        assertEquals("西侧 3 件之间不该夹进东侧的件", 2, westSpan)
    }
}
