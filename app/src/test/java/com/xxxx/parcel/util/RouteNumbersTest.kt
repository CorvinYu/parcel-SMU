package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 稳定件号测试（用户 2026-10-01 明确的两条规则）。
 *
 * 1. **不隐藏已取件** ⇒ 取完的那条留在原地、保留原号，其余件号不动（不许「消失并跑到最末尾」）
 * 2. **隐藏已取件** ⇒ 取完就消失、编号从 1 紧凑重排（这条保持原样即可）
 */
class RouteNumbersTest {

    @Test
    fun `不隐藏已取件时件号稳定且留在原地`() {
        val sticky = mutableMapOf<String, Int>()
        val all = listOf("A", "B", "C", "D")
        assertEquals(
            "首次分配应按取件顺序从 1 开始",
            mapOf("A" to 1, "B" to 2, "C" to 3, "D" to 4),
            assignStableNumbers(all, all, sticky),
        )
        // A 取完：因为「不隐藏」它仍在列表里 ⇒ 保住 1，B/C/D 也不许动
        assertEquals(
            "已取件的 A 必须留在原位、保住原号，其余件号不许变化",
            mapOf("A" to 1, "B" to 2, "C" to 3, "D" to 4),
            assignStableNumbers(all, listOf("B", "C", "D"), sticky),
        )
    }

    @Test
    fun `新来的件拿最小空闲号`() {
        val sticky = mutableMapOf("A" to 1, "B" to 2, "C" to 3)
        val m = assignStableNumbers(listOf("A", "B", "C", "E"), listOf("B", "C", "E"), sticky)
        assertEquals(4, m["E"])
    }

    @Test
    fun `地址离开列表后号被释放并复用`() {
        val sticky = mutableMapOf("A" to 1, "B" to 2, "C" to 3)
        // C 从列表消失（短信被删 / 该地址不再出现）⇒ 号 3 释放；新地址 D 应拿到 3
        val m = assignStableNumbers(listOf("A", "B", "D"), listOf("A", "B", "D"), sticky)
        assertEquals("空号应被复用，不许越编越大", 3, m["D"])
        assertEquals(mapOf("A" to 1, "B" to 2, "D" to 3), m)
    }

    @Test
    fun `隐藏已取件时从 1 紧凑重排`() {
        val sticky = mutableMapOf("A" to 1, "B" to 2, "C" to 3, "D" to 4)
        val m = compactNumbers(listOf("C", "D"), sticky)
        assertEquals("隐藏模式下编号从 1 连续排", mapOf("C" to 1, "D" to 2), m)
        assertTrue("紧凑排号后旧表应清空（否则号会越滚越大）", sticky.isEmpty())
    }

    @Test
    fun `反复调用是幂等的`() {
        val sticky = mutableMapOf<String, Int>()
        val all = listOf("A", "B", "C")
        val first = assignStableNumbers(all, all, sticky)
        val second = assignStableNumbers(all, all, sticky)
        val third = assignStableNumbers(all, listOf("A"), sticky)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    // ───────── 下拉刷新（用户 2026-10-01：首页下滑刷新排序） ─────────

    @Test
    fun `下拉刷新把未取件按新顺序重排`() {
        // 初始顺序 A B C D；用户下拉刷新后寻路给出新顺序 C D A B
        val sticky = mutableMapOf("A" to 1, "B" to 2, "C" to 3, "D" to 4)
        val shown = listOf("A", "B", "C", "D")
        val m = refreshStableNumbers(shown, listOf("C", "D", "A", "B"), sticky)
        assertEquals("刷新后按号排序应等于新的最优顺序", listOf("C", "D", "A", "B"), shown.sortedBy { m[it] })
        assertEquals("号必须仍是 1..N 不重不漏", setOf(1, 2, 3, 4), m.values.toSet())
    }

    @Test
    fun `下拉刷新时已取件留在原位保原号`() {
        // A 已取（号 1），未取件 B C D 的新最优顺序是 D B C
        val sticky = mutableMapOf("A" to 1, "B" to 2, "C" to 3, "D" to 4)
        val shown = listOf("A", "B", "C", "D")
        val m = refreshStableNumbers(shown, listOf("D", "B", "C"), sticky)
        assertEquals("已取件的 A 必须保原号 1（留在原位）", 1, m["A"])
        assertEquals(
            "未取件按新顺序依次拿空出来的号（2/3/4）",
            listOf("A", "D", "B", "C"),
            shown.sortedBy { m[it] },
        )
    }

    @Test
    fun `下拉刷新不重号也不跳号`() {
        // 人为构造「已取件占着 2 号」这种历史遗留状态（号与他人交错）
        val sticky = mutableMapOf("X" to 2, "A" to 3, "B" to 1)
        val shown = listOf("X", "A", "B", "C")
        val m = refreshStableNumbers(shown, listOf("C", "A", "B"), sticky)
        assertEquals("号码必须唯一", shown.size, m.values.toSet().size)
        assertEquals("已取件保原号", 2, m["X"])
        assertEquals("未取件按新顺序：C 在 A 之前、A 在 B 之前", listOf("C", "A", "B"), listOf("C", "A", "B").sortedBy { m[it] })
        assertTrue("未取件不该抢已取件的号", m.values.count { it == 2 } == 1)
    }

    @Test
    fun `下拉刷新会释放已经不在列表里的地址的号`() {
        val sticky = mutableMapOf("A" to 1, "B" to 2, "Z" to 3)
        val shown = listOf("A", "B")
        val m = refreshStableNumbers(shown, listOf("B", "A"), sticky)
        assertEquals("Z 已不在列表 ⇒ 号被释放，不许留下空洞", setOf(1, 2), m.values.toSet())
        assertEquals("未取件按新顺序（B 先）", 1, m["B"])
    }

    @Test
    fun `下拉刷新在『取到一半』时也自洽`() {
        // 4 件里取了 B（号 2 留着），剩下 A C D 新顺序 D C A
        val sticky = mutableMapOf("A" to 1, "B" to 2, "C" to 3, "D" to 4)
        val shown = listOf("A", "B", "C", "D")
        val m = refreshStableNumbers(shown, listOf("D", "C", "A"), sticky)
        assertEquals(2, m["B"])
        assertEquals("已取件在 2 号原位；未取件按 D→C→A 拿 1/3/4", listOf("D", "B", "C", "A"), listOf("A", "B", "C", "D").sortedBy { m[it] })
        assertEquals(setOf(1, 2, 3, 4), m.values.toSet())
    }
}
