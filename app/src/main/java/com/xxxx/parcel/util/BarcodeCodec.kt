package com.xxxx.parcel.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer

/**
 * 条码编解码核心 —— **纯 Kotlin + ZXing，不依赖任何 Android 类**。
 *
 * 之所以从 `Barcode.kt` 里拆出来：这样它能在 JVM 上被单元测试直接跑（见 `BarcodeCodecTest`），
 * 不需要真机、不需要 Robolectric。也正因为可测，下面两条关键行为才有了硬证据：
 *
 * 1. **「条码只占画面中心一小块」时能否识别** —— 用合成截图复现该场景。
 * 2. **「铺满全屏」重绘后是否仍然可扫** —— 直接渲染成 1080×2400 再解回来。
 *
 * ## 一维条码的等比性质（这是「铺满不变形」的机制保证）
 *
 * ZXing 的 `OneDimensionalCodeWriter.renderResult` 会：
 * 取 `outputWidth = max(请求宽度, 最小宽度)`，算出**整数倍** `multiple = outputWidth / fullWidth`，
 * 再按 `multiple` 逐条绘制并把剩余宽度均分成左右静区。
 *
 * ⇒ 条宽只会被**整数倍放大**，绝不会出现「有的条放大 2.3 倍、有的放大 2.7 倍」这种
 * 破坏条宽比例的情况。因此把请求高度给到全屏是安全的（条是竖直的，拉高不改变横向编码）。
 */
private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/** 支持重绘的码制。默认 Code128 —— 校园快递通行码绝大多数是它。 */
enum class BarcodeSymbology(val value: String, val label: String, val zxingFormat: BarcodeFormat) {
    CODE_128("code128", "Code128（推荐）", BarcodeFormat.CODE_128),
    CODE_39("code39", "Code39", BarcodeFormat.CODE_39),
    ITF("itf", "ITF", BarcodeFormat.ITF),
    EAN_13("ean13", "EAN-13", BarcodeFormat.EAN_13),
    QR_CODE("qr", "二维码 QR", BarcodeFormat.QR_CODE);

    companion object {
        fun fromValue(value: String?): BarcodeSymbology =
            entries.firstOrNull { it.value == value } ?: CODE_128
    }
}

/** 识别时尝试的码制（比可重绘的列表宽，尽量先认出内容再说）。 */
private val DECODE_FORMATS = listOf(
    BarcodeFormat.CODE_128,
    BarcodeFormat.CODE_39,
    BarcodeFormat.CODE_93,
    BarcodeFormat.ITF,
    BarcodeFormat.CODABAR,
    BarcodeFormat.EAN_13,
    BarcodeFormat.EAN_8,
    BarcodeFormat.UPC_A,
    BarcodeFormat.UPC_E,
    BarcodeFormat.QR_CODE,
    BarcodeFormat.DATA_MATRIX
)

/**
 * 把内容重绘成黑白像素图。
 *
 * @return ARGB 像素数组；内容与码制不匹配（如用 EAN-13 编非数字内容）时返回 null。
 */
fun renderBarcodePixels(
    payload: String,
    symbology: BarcodeSymbology,
    widthPx: Int,
    heightPx: Int,
): PixelImageResult? {
    if (payload.isBlank() || widthPx <= 0 || heightPx <= 0) return null
    return try {
        val matrix = MultiFormatWriter().encode(payload, symbology.zxingFormat, widthPx, heightPx)
        PixelImageResult(
            width = matrix.width,
            height = matrix.height,
            pixels = bitMatrixToPixels(matrix)
        )
    } catch (_: Exception) {
        null
    }
}

/** 该内容能否用指定码制重绘。 */
fun canEncodeBarcode(payload: String, symbology: BarcodeSymbology): Boolean =
    renderBarcodePixels(payload, symbology, 200, 60) != null

fun bitMatrixToPixels(matrix: BitMatrix): IntArray {
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    var i = 0
    for (y in 0 until h) {
        for (x in 0 until w) {
            pixels[i++] = if (matrix.get(x, y)) BLACK else WHITE
        }
    }
    return pixels
}

/** 一张 ARGB 像素图。 */
class PixelImageResult(val width: Int, val height: Int, val pixels: IntArray)

/** 在整图上识别一次。 */
fun decodeBarcodePixels(pixels: IntArray, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    return try {
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to DECODE_FORMATS
        )
        MultiFormatReader().apply { setHints(hints) }.decode(binary).text
    } catch (_: NotFoundException) {
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * 截图的识别策略，三级递进：
 *
 * 1. **整图** —— 条码占画面大部分时一步命中；
 * 2. **按「含深色内容的横带」切出来逐条识别** —— 这是截图导入的主力路径。
 *    实测（见 `BarcodeCodecTest`）：把一张 700×200 的条码贴进 1080×2400 的空白画布后，
 *    **整图识别会抛 NotFoundException，而贴着条码裁剪出来的同样像素立刻能解出**。
 *    原因是整图二值化在大片空白上失手，而不是条码本身有问题。
 * 3. **中心裁剪兜底** —— 二维码、低对比等一维横带切不出来的情况。
 */
fun decodeBarcodeFromScreenshot(pixels: IntArray, width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null

    decodeBarcodePixels(pixels, width, height)?.let { return it }

    val margin = maxOf(6, height / 120)
    for (band in darkRowBands(pixels, width, height)) {
        val top = (band.first - margin).coerceAtLeast(0)
        val bottom = (band.last + margin).coerceAtMost(height - 1)
        val bandHeight = bottom - top + 1
        if (bandHeight < 8) continue
        val cropped = cropRect(pixels, width, height, 0, top, width, bandHeight) ?: continue
        decodeBarcodePixels(cropped.pixels, cropped.width, cropped.height)?.let { return it }
    }

    for (ratio in listOf(0.6f, 0.4f)) {
        val cropped = cropCenter(pixels, width, height, ratio) ?: continue
        decodeBarcodePixels(cropped.pixels, cropped.width, cropped.height)?.let { return it }
    }
    return null
}

private const val DARK_LUMINANCE = 128

/**
 * 找出所有「含有深色像素」的连续行段，按内容量从多到少排序。
 *
 * 一维条码在截图里就是一条横向的深色带，所以这个直方图能把条码所在的行范围框出来，
 * 让识别只看那一小块，而不是整张手机截图。文字行同样会被框出来，但解不出条码，代价很低。
 */
fun darkRowBands(
    pixels: IntArray,
    width: Int,
    height: Int,
    maxBands: Int = 10,
): List<IntRange> {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()

    val darkCounts = IntArray(height)
    for (y in 0 until height) {
        val base = y * width
        var count = 0
        for (x in 0 until width) {
            val p = pixels[base + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if ((r * 30 + g * 59 + b * 11) / 100 < DARK_LUMINANCE) count++
        }
        darkCounts[y] = count
    }

    // 一行里至少要有若干个深色像素才算「有内容」，避免把噪点当内容
    val threshold = maxOf(3, width / 200)
    val bands = mutableListOf<IntRange>()
    var start = -1
    for (y in 0 until height) {
        val hasContent = darkCounts[y] >= threshold
        if (hasContent && start < 0) start = y
        if (!hasContent && start >= 0) {
            bands += start until y
            start = -1
        }
    }
    if (start >= 0) bands += start until height

    // 合并被少数空白行切开的相邻条（截图压缩噪声常造成断裂）
    val merged = mutableListOf<IntRange>()
    for (band in bands) {
        val last = merged.lastOrNull()
        if (last != null && band.first - last.last <= 12) {
            merged[merged.size - 1] = last.first..band.last
        } else {
            merged += band
        }
    }

    return merged
        .filter { it.last - it.first + 1 >= 6 }
        .sortedByDescending { range -> (range.first..range.last).sumOf { darkCounts[it] } }
        .take(maxBands)
}

/** 取任意矩形区域。 */
fun cropRect(
    pixels: IntArray,
    width: Int,
    height: Int,
    left: Int,
    top: Int,
    w: Int,
    h: Int,
): PixelImageResult? {
    if (w <= 0 || h <= 0) return null
    if (left < 0 || top < 0 || left + w > width || top + h > height) return null
    if (pixels.size < width * height) return null
    val out = IntArray(w * h)
    for (y in 0 until h) {
        System.arraycopy(pixels, (top + y) * width + left, out, y * w, w)
    }
    return PixelImageResult(w, h, out)
}

/**
 * 从截图里**裁出条码区域的原图**（不解码、不重编码）。
 *
 * 为什么需要这条路：实测用户的微信专属码截图时发现，
 * 条码**实际编码的内容**（11 字符）与页面上印的明文（8 字符）**并不一致**。
 * 此时「解码后重绘」等于把不确定的东西当成真值；而直接裁原图可以做到内容 **100% 保真**，
 * 扫码枪读到什么，重绘前就是什么。
 *
 * 实现：逐条深色横带尝试识别，**能解出条码的那一条**就是条码所在的带；
 * 再横向收紧到有深色像素的列范围（左右各留静区）。
 */
fun extractBarcodeImage(pixels: IntArray, width: Int, height: Int): PixelImageResult? {
    if (width <= 0 || height <= 0) return null
    val margin = maxOf(6, height / 120)
    for (band in darkRowBands(pixels, width, height)) {
        val top = (band.first - margin).coerceAtLeast(0)
        val bottom = (band.last + margin).coerceAtMost(height - 1)
        val bandHeight = bottom - top + 1
        if (bandHeight < 8) continue
        val crop = cropRect(pixels, width, height, 0, top, width, bandHeight) ?: continue
        if (decodeBarcodePixels(crop.pixels, crop.width, crop.height) != null) {
            return trimToBars(crop)
        }
    }
    return null
}

/** 横向收紧到条码本体的列范围，左右各留一段静区（静区不足会导致扫码枪读不到）。 */
private fun trimToBars(image: PixelImageResult): PixelImageResult {
    var left = image.width
    var right = -1
    for (x in 0 until image.width) {
        var hasDark = false
        for (y in 0 until image.height) {
            val p = image.pixels[y * image.width + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if ((r * 30 + g * 59 + b * 11) / 100 < DARK_LUMINANCE) {
                hasDark = true
                break
            }
        }
        if (hasDark) {
            if (x < left) left = x
            right = x
        }
    }
    if (right < 0 || right <= left) return image

    val padding = maxOf(8, image.height / 8)
    val start = (left - padding).coerceAtLeast(0)
    val end = (right + padding).coerceAtMost(image.width - 1)
    return cropRect(image.pixels, image.width, image.height, start, 0, end - start + 1, image.height)
        ?: image
}

/** 取中心区域（按比例）。 */
fun cropCenter(pixels: IntArray, width: Int, height: Int, ratio: Float): PixelImageResult? {
    if (ratio <= 0f || ratio > 1f) return null
    val w = (width * ratio).toInt()
    val h = (height * ratio).toInt()
    if (w <= 0 || h <= 0) return null
    val left = (width - w) / 2
    val top = (height - h) / 2
    val out = IntArray(w * h)
    for (y in 0 until h) {
        System.arraycopy(pixels, (top + y) * width + left, out, y * w, w)
    }
    return PixelImageResult(w, h, out)
}

/** 把一张小图贴到大图的指定位置（测试与合成场景用）。 */
fun pasteInto(
    destination: IntArray,
    destWidth: Int,
    destHeight: Int,
    source: PixelImageResult,
    left: Int,
    top: Int,
): Boolean {
    if (left < 0 || top < 0) return false
    if (left + source.width > destWidth || top + source.height > destHeight) return false
    for (y in 0 until source.height) {
        System.arraycopy(
            source.pixels, y * source.width,
            destination, (top + y) * destWidth + left,
            source.width
        )
    }
    return true
}

/** 生成一张纯色画布。 */
fun blankCanvas(width: Int, height: Int, color: Int = WHITE): IntArray =
    IntArray(width * height) { color }
