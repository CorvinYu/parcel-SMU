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
}
