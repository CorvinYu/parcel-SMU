package com.xxxx.parcel.ui

import com.xxxx.parcel.util.BarcodeSymbology
import com.xxxx.parcel.util.renderBarcodePixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「条码试验」页里内容整理逻辑的验证。
 *
 * 关键点：一维码对内容有硬要求（Code39 只认大写与少数符号、ITF 只认偶数位数字、
 * EAN-13 只认 12/13 位数字且校验位要对）。**变换错了就会表现为「柜机不认」**，
 * 让人误判是柜机的问题 —— 所以这里逐条钉住，并顺手用 ZXing 真跑一遍确认编得出来。
 */
class BarcodeLabVariantsTest {

    @Test
    fun `Code128 原样编码且总是存在`() {
        val v = labVariants("54018314")
        val c128 = v.first { it.symbology == BarcodeSymbology.CODE_128 }
        assertEquals("54018314", c128.payload)
    }

    @Test
    fun `ITF 必须是偶数位数字`() {
        val odd = labVariants("123456789").first { it.symbology == BarcodeSymbology.ITF }
        assertEquals("0123456789", odd.payload)   // 奇数位 ⇒ 左侧补一个 0

        val even = labVariants("54018314").first { it.symbology == BarcodeSymbology.ITF }
        assertEquals("54018314", even.payload)    // 偶数位 ⇒ 原样
    }

    @Test
    fun `EAN13 交给编码器算校验位（自己填第 13 位最容易算错编不出来）`() {
        val v = labVariants("12345").first { it.symbology == BarcodeSymbology.EAN_13 }
        assertEquals("000000012345", v.payload)                       // 12 位内容
        assertTrue("提示里要写出实际 13 位", v.note.contains("0000000123457"))
    }

    @Test
    fun `EAN13 校验位算法`() {
        // 教科书例子：490123456789 → 校验位 4
        assertEquals('4', ean13CheckDigit("490123456789"))
        assertEquals('7', ean13CheckDigit("000000012345"))
    }

    @Test
    fun `Code39 只保留大写与允许的符号`() {
        val v = labVariants("ab-12/x").first { it.symbology == BarcodeSymbology.CODE_39 }
        assertEquals("AB-12/X", v.payload)
    }

    @Test
    fun `二维码原样编码`() {
        val v = labVariants("D8-6").first { it.symbology == BarcodeSymbology.QR_CODE }
        assertEquals("D8-6", v.payload)
    }

    @Test
    fun `空输入不产生任何码制`() {
        assertTrue(labVariants("   ").isEmpty())
    }

    @Test
    fun `每一种码制都真的编得出来（借 ZXing 实跑）`() {
        val variants = labVariants("54018314")
        assertTrue("至少要有 Code128/Code39/ITF/EAN13/QR 五种", variants.size >= 5)
        variants.forEach { v ->
            assertNotNull(
                "${v.symbology.label} 编不出来（实际内容=${v.payload}），柜机上肯定扫不到",
                renderBarcodePixels(v.payload, v.symbology, 400, 200),
            )
        }
    }
}
