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
}
