package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 短信解析的验证（尤其是**货格号**这一路）。
 *
 * 背景：路线功能一直拿不到输入，因为 `compartmentNumber` 这套上游正则只认「格口」/「N号柜」，
 * 也就是**只对快递柜有值**；人工货架的货格号（`D8-6`）从来没被提取过。
 * 这里用真实短信格式把这件事钉死，防止再退化。
 */
class SmsParserTest {

    private val parser = SmsParser()

    @Test
    fun `人工货架短信要能解析出取件码与货格号`() {
        // ⚠️ 这条短信是「据用户描述」拼出来的**近似样本**，确切原文仍待用户提供。
        //    因此这里只断言与「路线功能拿不到输入」直接相关的两项：取件码 + 货格号。
        //    success（地址能否解析）取决于真实原文，不能拿合成样本当标准（见下一条测试）。
        val r = parser.parseSms("【菜鸟驿站】您的包裹已到上海海事大学快递站，请用D8-6到人工货架取包裹")
        assertTrue("取件码应含 D8-6，实际=${r.code}", r.code.contains("D8-6"))
        assertEquals("人工货架的货格号必须被提取出来", "D8-6", r.compartmentNumber)
    }

    @Test
    fun `地址解析对人工货架短信仍然失败（待真实短信样本来修）`() {
        // 现状记录：addressPattern 的形状是「关键词 + [\w\s-]+? + (驿站|快递点|柜|标点|$)」，
        // 而 Java 默认 \w 不含中文 ⇒ 「上海海事大学快递站，…」这种串匹配不上，success=false，
        // 短信会被丢进「解析失败」、根本进不了列表 —— 这才是路线页为空的**上游原因**。
        // 一旦拿到用户的真实短信原文把地址规则补齐，这条测试会失败，到时删掉它并补上真实断言。
        val r = parser.parseSms("【菜鸟驿站】您的包裹已到上海海事大学快递站，请用D8-6到人工货架取包裹")
        assertFalse(
            "地址规则似乎已能处理这类短信（address='${r.address}'），请把本测试换成真实样本断言",
            r.success,
        )
    }

    @Test
    fun `顺丰三段式货格号`() {
        val r = parser.parseSms("【顺丰速运】您的快件已到达上海海事大学，取件码为S3-2-2628，请及时领取")
        assertEquals("S3-2-2628", r.compartmentNumber)
    }

    @Test
    fun `大件三段式货格号`() {
        val r = parser.parseSms("【快递中心】您的包裹已到上海海事大学，取件码为Y5-7-1，请到工作人员处领取")
        assertEquals("Y5-7-1", r.compartmentNumber)
    }

    @Test
    fun `快递柜的格口号不能被人工货架规则劫持`() {
        val r = parser.parseSms("【丰巢】您的包裹已放入7号柜23-32格口，请及时取件")
        assertEquals("23-32", r.compartmentNumber)
        assertEquals("7", r.lockerNumber)
    }

    @Test
    fun `纯数字取件码不产生货格号`() {
        val r = parser.parseSms("【快递柜】您的取件码为54018314，请及时取件")
        assertEquals("", r.compartmentNumber)
    }

    // ===== 货格号提取函数本身的边界（纯逻辑，独立测） =====

    @Test
    fun `从取件码里识别货格号`() {
        assertEquals("D8-6", compartmentFromPickupCode("D8-6"))
        assertEquals("B4-18", compartmentFromPickupCode("b4-18"))
        assertEquals("S3-2-2628", compartmentFromPickupCode("S3-2-2628"))
        assertEquals("Y8-1-3", compartmentFromPickupCode("Y8-1-3"))
        assertEquals("A11", compartmentFromPickupCode("A11"))
        // 多个取件码取第一个像货格号的
        assertEquals("A11", compartmentFromPickupCode("A11, B12"))
    }

    @Test
    fun `不该被当成货格号的取件码`() {
        assertEquals(null, compartmentFromPickupCode("54018314"))   // 纯数字 ⇒ 快递柜
        assertEquals(null, compartmentFromPickupCode("8-3-2018"))   // 无排字母
        assertEquals(null, compartmentFromPickupCode("23-32"))      // 格口
        assertEquals(null, compartmentFromPickupCode("SF1234567890")) // 字母+字母 ⇒ 运单号
        assertEquals(null, compartmentFromPickupCode("JD12345678"))
        assertEquals(null, compartmentFromPickupCode(""))
        assertEquals(null, compartmentFromPickupCode("取件码"))
    }

    @Test
    fun `有效货格号：解析出的优先，为空时用取件码兜底`() {
        assertEquals("23-32", effectiveCompartmentNumber("23-32", "54018314"))  // 快递柜：用解析值
        assertEquals("D8-6", effectiveCompartmentNumber("", "D8-6"))            // 人工货架：兜底
        assertEquals("D8-6", effectiveCompartmentNumber("   ", "d8-6"))         // 归一化大写
        assertEquals("", effectiveCompartmentNumber("", "54018314"))            // 纯数字不兜底
    }
}
