package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已取件记录的两条纯逻辑（用户 2026-10-01 实测反馈逼出来的）：
 *
 * 1. **起点**：只认时间窗内最近的一条，过期/为空返回 null（隔夜再从入口进）；
 * 2. **顺丰出库这一步必须留着**：把 S 件的取件码标记为已取后，路线里就没有 S 件了
 *    （`hasSfCheckout` 变 false），但它不能因此消失 —— 判断依据改成「本次行程取过 S 件」。
 */
class CheckoutOriginTest {

    @Test
    fun `窗口内取过顺丰件即为真`() {
        assertTrue(containsSfCheckout(listOf("S3-2-2628" to 1_000L)))
        assertTrue("混着普通件也应为真", containsSfCheckout(listOf("D8-6" to 1_000L, "S1-5-2871" to 900L)))
    }

    @Test
    fun `只有普通件或空记录时为假`() {
        assertFalse(containsSfCheckout(emptyList()))
        assertFalse(containsSfCheckout(listOf("D8-6" to 1_000L, "J5-21" to 900L, "Y5-7-1" to 800L)))
    }

    @Test
    fun `解析不了的取件码不会被误判成顺丰`() {
        // 纯数字是快递柜取件码；有歧义的写法（D523）也要拒绝，不能猜
        assertFalse(containsSfCheckout(listOf("54018314" to 1_000L)))
        assertFalse(containsSfCheckout(listOf("D523" to 1_000L)))
    }

    @Test
    fun `起点取窗口内最近的一条`() {
        val entries = listOf("C1" to 10_000L, "B1" to 9_000L)
        assertEquals("C1", pickCheckoutOriginCode(entries, now = 10_000L, windowMs = 3_600_000L))
    }

    @Test
    fun `起点超出时间窗或为空时为 null`() {
        val now = 10_000_000L
        assertNull(
            "3 小时前的记录不该再当起点（隔夜从入口进）",
            pickCheckoutOriginCode(listOf("C1" to now - 3_600_001L), now, windowMs = 3_600_000L),
        )
        assertNull(pickCheckoutOriginCode(emptyList(), now))
        assertNull("时间戳在未来（设备改时间）也当成不可用", pickCheckoutOriginCode(listOf("C1" to now + 1000L), now))
    }

    // ───────── 「顺丰出库」卡片右侧的件数提醒（测试功能） ─────────

    @Test
    fun `待出库顺丰件数按件去重`() {
        assertEquals(
            "取过 3 件顺丰 ⇒ 提醒 3 件",
            3,
            countSfCheckouts(listOf("S3-2-2628" to 1L, "S1-5-2871" to 2L, "S2-1-9" to 3L)),
        )
    }

    @Test
    fun `普通件不计入待出库顺丰件数`() {
        assertEquals(
            1,
            countSfCheckouts(listOf("D8-6" to 1L, "J5-21" to 2L, "Y5-7-1" to 3L, "S1-1-1" to 4L)),
        )
        assertEquals(0, countSfCheckouts(emptyList()))
        assertEquals("解析不了的码不算", 0, countSfCheckouts(listOf("54018314" to 1L)))
    }

    @Test
    fun `同一条记录重复出现只算一件`() {
        assertEquals(1, countSfCheckouts(listOf("S3-2-2628" to 2L, "S3-2-2628" to 1L)))
    }
}
