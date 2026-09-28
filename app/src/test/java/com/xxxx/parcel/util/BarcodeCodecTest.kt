package com.xxxx.parcel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条码编解码核心的验证（纯 JVM，不需要真机）。
 *
 * 这组测试针对的是两个**只在真机上才容易暴露、但可以在合成图像上提前验证**的关键行为：
 * 1. 微信页面截图里条码只占画面中心一小块时，识别策略是否管用；
 * 2. 「铺满全屏背景」重绘出来的条码是否**仍然可被扫出**（即横向没被拉变形）。
 */
class BarcodeCodecTest {

    private val white = 0xFFFFFFFF.toInt()

    // ===== 往返：重绘 → 解回 =====

    @Test
    fun `Code128 重绘后能被解回原内容`() {
        val payload = "SPU-2026-000123"
        val image = renderBarcodePixels(payload, BarcodeSymbology.CODE_128, 600, 200)
        assertNotNull("重绘不应失败", image)
        assertEquals(payload, decodeBarcodePixels(image!!.pixels, image.width, image.height))
    }

    @Test
    fun `各码制都能往返`() {
        val cases = listOf(
            BarcodeSymbology.CODE_128 to "SPU-2026-000123",
            BarcodeSymbology.CODE_39 to "SPU2026",
            BarcodeSymbology.ITF to "12345678",
            // 9780201379624 是校验位正确的合法 EAN-13
            BarcodeSymbology.EAN_13 to "9780201379624",
            BarcodeSymbology.QR_CODE to "https://example.com/spu/abc"
        )
        for ((symbology, payload) in cases) {
            val image = renderBarcodePixels(payload, symbology, 600, 220)
            assertNotNull("${symbology.label} 重绘失败", image)
            assertEquals(
                "${symbology.label} 往返不一致",
                payload,
                decodeBarcodePixels(image!!.pixels, image.width, image.height)
            )
        }
    }

    // ===== 场景一：条码只占画面中心 =====

    @Test
    fun `条码只占手机截图中心一小块时仍能识别`() {
        val payload = "1234567890"
        val screenW = 1080
        val screenH = 2400

        // 模拟几种「条码在截图里有多大」的情况：宽度占屏幕 50% / 35% / 25%
        val widths = listOf(540, 378, 270)
        for (barWidth in widths) {
            val barHeight = (barWidth / 3).coerceAtLeast(60)
            val bar = renderBarcodePixels(payload, BarcodeSymbology.CODE_128, barWidth, barHeight)!!
            val canvas = blankCanvas(screenW, screenH, white)
            val ok = pasteInto(
                canvas, screenW, screenH, bar,
                (screenW - bar.width) / 2,
                (screenH - bar.height) / 2
            )
            assertTrue("条码宽度 $barWidth 时贴图失败", ok)
            assertEquals(
                "条码只占屏幕宽度 ${barWidth * 100 / screenW}% 时应当仍能识别",
                payload,
                decodeBarcodeFromScreenshot(canvas, screenW, screenH)
            )
        }
    }

    @Test
    fun `偏心条码靠横带裁剪识别成功，而中心裁剪会把它横向切坏`() {
        val payload = "99887766"
        val screenW = 1080
        val screenH = 2400
        val bar = renderBarcodePixels(payload, BarcodeSymbology.CODE_128, 700, 200)!!
        val canvas = blankCanvas(screenW, screenH, white)
        // 贴在偏左位置：中心 60%/40% 的裁剪框会把条码左侧切掉
        assertTrue(pasteInto(canvas, screenW, screenH, bar, 60, 420))

        // 把「为什么必须按深色横带切」这件事固化成断言，避免以后又退回只用整图+中心裁剪
        assertNull(
            "整图识别在「条码嵌于大片空白」时会失败",
            decodeBarcodePixels(canvas, screenW, screenH)
        )
        for (ratio in listOf(0.6f, 0.4f)) {
            val cropped = cropCenter(canvas, screenW, screenH, ratio)!!
            assertNull(
                "中心 ${(ratio * 100).toInt()}% 裁剪会横向切坏条码",
                decodeBarcodePixels(cropped.pixels, cropped.width, cropped.height)
            )
        }

        // 完整策略（含横带裁剪）应当认得出来
        assertEquals(payload, decodeBarcodeFromScreenshot(canvas, screenW, screenH))
    }

    @Test
    fun `条码贴在截图底部时也能识别`() {
        val payload = "55443322"
        val bar = renderBarcodePixels(payload, BarcodeSymbology.CODE_128, 760, 200)!!
        val canvas = blankCanvas(1080, 2400, white)
        assertTrue(pasteInto(canvas, 1080, 2400, bar, 160, 2100))
        assertEquals(payload, decodeBarcodeFromScreenshot(canvas, 1080, 2400))
    }

    @Test
    fun `像微信页面那样上下都有文字时仍能识别`() {
        val payload = "6677889900"
        val canvas = blankCanvas(1080, 2400, white)
        // 造几条「文字行」（短深色横条），模拟页面上的标题与说明文字
        val textColor = 0xFF333333.toInt()
        for (rowTop in listOf(180, 230, 280, 1960, 2010, 2060)) {
            for (y in rowTop until rowTop + 16) {
                for (x in 120 until 940) canvas[y * 1080 + x] = textColor
            }
        }
        val bar = renderBarcodePixels(payload, BarcodeSymbology.CODE_128, 800, 220)!!
        assertTrue(pasteInto(canvas, 1080, 2400, bar, 140, 900))
        assertEquals(payload, decodeBarcodeFromScreenshot(canvas, 1080, 2400))
    }

    @Test
    fun `深色横带能把条码所在的行范围框出来`() {
        val canvas = blankCanvas(1080, 2400, white)
        val bar = renderBarcodePixels("12345678", BarcodeSymbology.CODE_128, 700, 200)!!
        assertTrue(pasteInto(canvas, 1080, 2400, bar, 60, 420))
        val bands = darkRowBands(canvas, 1080, 2400)
        assertTrue("应当至少框出一条内容带", bands.isNotEmpty())
        assertTrue(
            "条码所在的 420~619 行应当被某条带覆盖（实际首条=${bands.first()}）",
            bands.any { it.first <= 420 && it.last >= 619 }
        )
    }

    // ===== 场景二：铺满全屏后是否仍可扫 =====

    @Test
    fun `铺满全屏尺寸重绘后仍能解出原内容`() {
        // Theme.kt 里就是这么渲染的：按屏幕像素直接生成，然后铺满
        val payload = "1234567890"
        val image = renderBarcodePixels(payload, BarcodeSymbology.CODE_128, 1080, 2400)!!
        assertEquals(1080, image.width)
        assertEquals(2400, image.height)
        assertEquals(
            "铺满全屏后条码必须仍然可解（否则「铺满背景」这个形态就是废的）",
            payload,
            decodeBarcodePixels(image.pixels, image.width, image.height)
        )
    }

    @Test
    fun `铺满时条宽只是被整数倍放大，不会出现不合比例的拉伸`() {
        val payload = "1234567890"
        val small = renderBarcodePixels(payload, BarcodeSymbology.CODE_128, 400, 100)!!
        val full = renderBarcodePixels(payload, BarcodeSymbology.CODE_128, 1080, 2400)!!

        // 取中间一行，比较两者的黑白游程宽度之比是否处处一致（整数倍缩放的特征）
        val smallRuns = runLengths(middleRow(small))
        val fullRuns = runLengths(middleRow(full))

        assertEquals("条的数量应当一致", smallRuns.size, fullRuns.size)

        val ratios = smallRuns.indices.map { i ->
            fullRuns[i].toDouble() / smallRuns[i].toDouble()
        }
        val maxRatio = ratios.max()
        val minRatio = ratios.min()
        // 允许 ±1 像素的取整误差；若发生横向非等比拉伸，这个差值会远大于此
        assertTrue(
            "游程比例应当基本一致（实际 min=$minRatio max=$maxRatio）",
            maxRatio - minRatio <= 0.5
        )
    }

    @Test
    fun `渲染结果左右留有静区`() {
        val image = renderBarcodePixels("1234567890", BarcodeSymbology.CODE_128, 1080, 2400)!!
        for (y in 0 until image.height step 97) {
            assertEquals("第 $y 行首列应为静区白", white, image.pixels[y * image.width])
            assertEquals("第 $y 行末列应为静区白", white, image.pixels[y * image.width + image.width - 1])
        }
    }

    // ===== 异常与边界 =====

    @Test
    fun `纯白图片识别不出内容且不抛异常`() {
        val canvas = blankCanvas(1080, 2400, white)
        assertNull(decodeBarcodeFromScreenshot(canvas, 1080, 2400))
    }

    @Test
    fun `内容与码制不匹配时重绘返回 null`() {
        // EAN-13 只能编数字
        assertNull(renderBarcodePixels("abc", BarcodeSymbology.EAN_13, 400, 150))
        assertTrue(!canEncodeBarcode("abc", BarcodeSymbology.EAN_13))
        assertTrue(canEncodeBarcode("9780201379624", BarcodeSymbology.EAN_13))
    }

    @Test
    fun `空内容与非法尺寸一律返回 null`() {
        assertNull(renderBarcodePixels("", BarcodeSymbology.CODE_128, 400, 100))
        assertNull(renderBarcodePixels("   ", BarcodeSymbology.CODE_128, 400, 100))
        assertNull(renderBarcodePixels("abc", BarcodeSymbology.CODE_128, 0, 100))
        assertNull(renderBarcodePixels("abc", BarcodeSymbology.CODE_128, 400, -1))
        assertNull(decodeBarcodePixels(IntArray(10), 0, 0))
        assertNull(cropCenter(IntArray(100), 10, 10, 0f))
        assertNull(cropCenter(IntArray(100), 10, 10, 1.5f))
    }

    @Test
    fun `cropCenter 取的是正中心且尺寸正确`() {
        val width = 100
        val height = 100
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        // 把正中心 20x20 涂白，用于验证裁剪窗口的定位
        for (y in 40 until 60) for (x in 40 until 60) pixels[y * width + x] = white

        val cropped = cropCenter(pixels, width, height, 0.4f)!!
        assertEquals(40, cropped.width)
        assertEquals(40, cropped.height)
        // 中心点应当仍是白色
        assertEquals(white, cropped.pixels[20 * cropped.width + 20])
        // 裁剪框左上角对应原图 (30,30)，应当是黑的
        assertEquals(0xFF000000.toInt(), cropped.pixels[0])
    }

    @Test
    fun `pasteInto 越界时拒绝而不是越界写`() {
        // 注意：ZXing 返回的宽度可能**大于**请求宽度（它保证不小于码的最小尺寸），
        // 所以边界必须按 bar.width / bar.height 实算，不能拿请求值当尺寸。
        val canvas = blankCanvas(500, 300, white)
        val bar = renderBarcodePixels("12345", BarcodeSymbology.CODE_128, 60, 20)!!
        val leftOverflow = 500 - bar.width + 1
        val topOverflow = 300 - bar.height + 1
        assertTrue(!pasteInto(canvas, 500, 300, bar, -1, 0))
        assertTrue(!pasteInto(canvas, 500, 300, bar, 0, -1))
        assertTrue("宽度方向越界应被拒绝", !pasteInto(canvas, 500, 300, bar, leftOverflow, 0))
        assertTrue("高度方向越界应被拒绝", !pasteInto(canvas, 500, 300, bar, 0, topOverflow))
        assertTrue(pasteInto(canvas, 500, 300, bar, 20, 40))
    }

    @Test
    fun `能从合成截图里裁出条码原图，且不含大片空白`() {
        val payload = "1234567890"
        val bar = renderBarcodePixels(payload, BarcodeSymbology.CODE_128, 700, 200)!!
        val canvas = blankCanvas(1080, 2400, white)
        assertTrue(pasteInto(canvas, 1080, 2400, bar, 60, 420))

        val extracted = extractBarcodeImage(canvas, 1080, 2400)
        assertNotNull("应当能裁出条码原图", extracted)
        assertTrue("高度应收窄到条码附近（实际 ${extracted!!.height}）", extracted.height < 400)
        assertTrue("宽度应收窄掉两侧空白（实际 ${extracted.width}）", extracted.width < 1080)
        assertEquals(
            "裁出的原图必须仍可被识别（原图模式就靠它保真）",
            payload,
            decodeBarcodePixels(extracted.pixels, extracted.width, extracted.height)
        )
    }

    @Test
    fun `裁不出条码时返回 null 而不是乱给一块图`() {
        val blank = blankCanvas(800, 1200, white)
        assertNull(extractBarcodeImage(blank, 800, 1200))
    }

    // ===== 工具 =====

    private fun middleRow(image: PixelImageResult): IntArray {
        val y = image.height / 2
        return IntArray(image.width) { x -> image.pixels[y * image.width + x] }
    }

    /** 返回黑白游程长度（从第一个黑开始的连续段长度序列，忽略两端静区）。 */
    private fun runLengths(row: IntArray): List<Int> {
        val black = 0xFF000000.toInt()
        var start = 0
        while (start < row.size && row[start] != black) start++
        var end = row.size - 1
        while (end >= 0 && row[end] != black) end--
        if (start > end) return emptyList()

        val runs = mutableListOf<Int>()
        var current = row[start]
        var count = 0
        for (i in start..end) {
            if (row[i] == current) {
                count++
            } else {
                runs += count
                current = row[i]
                count = 1
            }
        }
        runs += count
        return runs
    }
}
