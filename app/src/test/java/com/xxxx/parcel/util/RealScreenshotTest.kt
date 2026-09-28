package com.xxxx.parcel.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Ignore
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * 用**真实截图**做的端到端识别验证（本地专属，不随仓库分发）。
 *
 * 数据放在 `<项目>/.devtools/testdata/`（已 gitignore）：
 * - `xxx.rgb`           由 `.devtools/jpg2rgb.py` 从截图转出的裸 RGB（格式见该脚本头部说明）
 * - `xxx.expected.txt`  期望解出的条码内容
 *
 * 为什么用裸 RGB 而不是直接读 JPEG：Android 单元测试的编译类路径以 android.jar 为平台，
 * **没有 javax.imageio**，ImageIO 不可用；裸 RGB 只用 java.io。
 *
 * ⚠️ `expected.txt` 目前记录的是**本解码器在该截图上的实测输出**，用作**回归锁定**
 * （防止以后改动让识别退化）。它是否等于驿站扫码枪读到的真值，尚待用户核实：
 * 实测发现该条码编码的内容（11 字符）与页面上印的明文（8 字符）**并不一致**。
 *
 * testdata 不存在或没有配对数据时整组自动跳过。
 */
class RealScreenshotTest {

    private val testDataDir: File? = listOf(
        File("../.devtools/testdata"),
        File(".devtools/testdata"),
    ).firstOrNull { dir ->
        dir.isDirectory && dir.listFiles()?.any { it.extension == "rgb" } == true
    }

    @Test
    fun `真实截图能被解出条码内容`() {
        val dir = testDataDir
        assumeTrue("没有 .devtools/testdata/*.rgb，跳过（先跑 .devtools/jpg2rgb.py）", dir != null)

        val cases = dir!!.listFiles()!!
            .filter { it.extension == "rgb" }
            .map { rgb ->
                rgb to File(rgb.parentFile, rgb.nameWithoutExtension + ".expected.txt")
            }
            .filter { (_, expected) -> expected.isFile }
            .sortedBy { (rgb, _) -> rgb.name }

        assumeTrue("testdata 里没有「rgb + 期望值」配对，跳过", cases.isNotEmpty())

        val failures = mutableListOf<String>()
        for ((rgb, expectedFile) in cases) {
            val expected = expectedFile.readText().trim()
            val frame = readRgb(rgb)
            if (frame == null) {
                failures += "${rgb.name}: 裸 RGB 文件格式不对"
                continue
            }
            val (pixels, width, height) = frame
            val decoded = decodeBarcodeFromScreenshot(pixels, width, height)
            if (decoded != expected) {
                failures += "${rgb.name}: 期望=[$expected] 实际=[$decoded]"
            }
        }
        assertTrue("真实截图识别失败 -> " + failures.joinToString("；"), failures.isEmpty())
    }

    @Test
    fun `真实截图能裁出条码原图，且裁出的图仍可被识别`() {
        val dir = testDataDir
        assumeTrue("没有 testdata，跳过", dir != null)
        val rgb = dir!!.listFiles()!!.first { it.extension == "rgb" }
        val (pixels, width, height) = readRgb(rgb)!!

        val extracted = extractBarcodeImage(pixels, width, height)
        assertTrue("应当能从真实截图里裁出条码原图", extracted != null)
        assertTrue(
            "裁出的图应当明显小于整张截图（实际 ${extracted!!.width}x${extracted.height}，整图 ${width}x$height）",
            extracted.width <= width && extracted.height < height / 2
        )
        assertTrue(
            "裁出的原图必须仍可被识别",
            decodeBarcodePixels(extracted.pixels, extracted.width, extracted.height) != null
        )
    }

    /**
     * 诊断：把真实截图在各策略下的**码制与原文**全部吐出来。
     * 只在排查「解出来了但内容不对」时手动去掉 @Ignore 跑一次。
     */
    @Ignore("临时诊断用：需要时去掉本注解再跑，结论会以断言消息形式给出")
    @Test
    fun `诊断-真实截图的识别细节`() {
        val dir = testDataDir
        assumeTrue("没有 testdata，跳过", dir != null)
        val rgb = dir!!.listFiles()!!.firstOrNull { it.extension == "rgb" } ?: return
        val (pixels, width, height) = readRgb(rgb)!!

        val lines = mutableListOf<String>()
        val bands = darkRowBands(pixels, width, height)
        lines += "尺寸=${width}x$height"
        lines += "内容带数=${bands.size} 前4=${bands.take(4)}"
        lines += "【整图】" + tryDecode(pixels, width, height, global = false)
        for ((index, band) in bands.take(6).withIndex()) {
            val top = (band.first - 12).coerceAtLeast(0)
            val bottom = (band.last + 12).coerceAtMost(height - 1)
            val crop = cropRect(pixels, width, height, 0, top, width, bottom - top + 1)!!
            lines += "【带$index ${band.first}..${band.last}】" +
                "原样=" + tryDecode(crop.pixels, crop.width, crop.height, global = false) +
                " 全局直方图=" + tryDecode(crop.pixels, crop.width, crop.height, global = true)
        }
        val extracted = extractBarcodeImage(pixels, width, height)
        lines += "【裁出的原图】" + (
            extracted?.let { "尺寸=${it.width}x${it.height} " + tryDecode(it.pixels, it.width, it.height, false) }
                ?: "null"
            )
        throw AssertionError(lines.joinToString(" || "))
    }

    private fun tryDecode(pixels: IntArray, width: Int, height: Int, global: Boolean): String {
        return try {
            val source = RGBLuminanceSource(width, height, pixels)
            val binary = if (global) {
                BinaryBitmap(GlobalHistogramBinarizer(source))
            } else {
                BinaryBitmap(HybridBinarizer(source))
            }
            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.CODE_93,
                    BarcodeFormat.ITF,
                    BarcodeFormat.CODABAR,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.UPC_A,
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.DATA_MATRIX
                )
            )
            val result = MultiFormatReader().apply { setHints(hints) }.decode(binary)
            result.barcodeFormat.name + ":" + result.text
        } catch (e: Throwable) {
            "×" + e::class.java.simpleName
        }
    }

    private fun upscale(image: PixelImageResult, factor: Int): PixelImageResult {
        val width = image.width * factor
        val height = image.height * factor
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val sourceY = y / factor
            for (x in 0 until width) {
                out[y * width + x] = image.pixels[sourceY * image.width + x / factor]
            }
        }
        return PixelImageResult(width, height, out)
    }

    private fun readRgb(file: File): Triple<IntArray, Int, Int>? {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val magic = ByteArray(4)
            input.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "PRGB") return null
            val width = input.readInt()
            val height = input.readInt()
            if (width <= 0 || height <= 0) return null
            val raw = ByteArray(width * height * 3)
            input.readFully(raw)

            val pixels = IntArray(width * height)
            var offset = 0
            for (i in pixels.indices) {
                val r = raw[offset].toInt() and 0xFF
                val g = raw[offset + 1].toInt() and 0xFF
                val b = raw[offset + 2].toInt() and 0xFF
                offset += 3
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            return Triple(pixels, width, height)
        }
    }
}
