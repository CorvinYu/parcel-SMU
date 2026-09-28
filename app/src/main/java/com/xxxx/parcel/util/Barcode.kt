package com.xxxx.parcel.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 微信快递中心「进出条码」的识别 / 重绘 / 持久化。
 *
 * 背景与设计约束（详见项目 NOTES.md）：
 * - 该条码是**静态**的一维 Code128，截图一次可长期使用。
 * - 一维条码只能**横向等比**缩放；纵向可任意拉伸。非等比横向拉伸会导致扫码枪读不出。
 * - 用 ZXing 而非 ML Kit：前者纯 Java、完全离线，符合本 app「不联网」定位。
 */
private const val PREFS_NAME = "parcel_prefs"
private const val KEY_PAYLOAD = "barcode_payload"
private const val KEY_SYMBOLOGY = "barcode_symbology"
private const val KEY_STRIP = "barcode_strip_enabled"
private const val KEY_BACKGROUND = "barcode_background_enabled"
private const val KEY_UPDATED_AT = "barcode_updated_at"
private const val TAG = "ParcelBarcode"

/** 一个条码最多渲染到这么大，避免超大截图 OOM。 */
private const val MAX_DECODE_DIMEN = 2000

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

private fun barcodePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

// ===== 读写设置 =====

fun getBarcodePayload(context: Context): String? =
    barcodePrefs(context).getString(KEY_PAYLOAD, null)?.takeIf { it.isNotBlank() }

fun saveBarcodePayload(context: Context, payload: String) {
    barcodePrefs(context).edit()
        .putString(KEY_PAYLOAD, payload)
        .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        .apply()
}

fun clearBarcodePayload(context: Context) {
    barcodePrefs(context).edit().remove(KEY_PAYLOAD).remove(KEY_UPDATED_AT).apply()
}

fun getBarcodeUpdatedAt(context: Context): Long = barcodePrefs(context).getLong(KEY_UPDATED_AT, 0L)

fun getBarcodeSymbology(context: Context): BarcodeSymbology =
    BarcodeSymbology.fromValue(barcodePrefs(context).getString(KEY_SYMBOLOGY, null))

fun saveBarcodeSymbology(context: Context, symbology: BarcodeSymbology) {
    barcodePrefs(context).edit().putString(KEY_SYMBOLOGY, symbology.value).apply()
}

/** 首页顶部常驻条码条 */
fun isBarcodeStripEnabled(context: Context): Boolean =
    barcodePrefs(context).getBoolean(KEY_STRIP, false)

fun saveBarcodeStripEnabled(context: Context, enabled: Boolean) {
    barcodePrefs(context).edit().putBoolean(KEY_STRIP, enabled).apply()
}

/** 铺满首页背景 */
fun isBarcodeBackgroundEnabled(context: Context): Boolean =
    barcodePrefs(context).getBoolean(KEY_BACKGROUND, false)

fun saveBarcodeBackgroundEnabled(context: Context, enabled: Boolean) {
    barcodePrefs(context).edit().putBoolean(KEY_BACKGROUND, enabled).apply()
}

// ===== 从截图识别 =====

/**
 * 从相册/截图 Uri 中解出条码内容。
 * 先整图识别；失败再按「中心区域」裁剪重试（微信页面里的条码通常只占屏幕中间一小块）。
 */
fun decodeBarcodeFromUri(context: Context, uri: Uri): String? {
    val bitmap = loadBitmapFromUri(context, uri) ?: return null
    return decodeBarcodeFromBitmap(bitmap)
}

fun decodeBarcodeFromBitmap(bitmap: Bitmap): String? {
    // 1) 整图
    decodeWithZxing(bitmap)?.let { return it }

    // 2) 中心裁剪（60% / 40%），应对条码只占画面中央的情况
    for (ratio in listOf(0.6f, 0.4f)) {
        val cropped = cropCenter(bitmap, ratio) ?: continue
        decodeWithZxing(cropped)?.let { return it }
    }
    return null
}

private fun decodeWithZxing(bitmap: Bitmap): String? {
    return try {
        val scaled = scaleDownIfNeeded(bitmap)
        val width = scaled.width
        val height = scaled.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))

        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(
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
        )
        val reader = MultiFormatReader().apply { setHints(hints) }
        reader.decode(binary).text
    } catch (e: Exception) {
        // NotFoundException 是常态（该裁剪区域没有条码），不刷日志
        if (e !is com.google.zxing.NotFoundException) {
            Log.w(TAG, "decode failed: ${e.message}")
        }
        null
    }
}

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_DECODE_DIMEN) {
                    decoder.setTargetSampleSize(Math.ceil(longest.toDouble() / MAX_DECODE_DIMEN).toInt())
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri), null, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = if (longest > MAX_DECODE_DIMEN) {
                        Math.ceil(longest.toDouble() / MAX_DECODE_DIMEN).toInt()
                    } else 1
                }
                BitmapFactory.decodeStream(input, null, options)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "load image failed: ${e.message}")
        null
    }
}

private fun cropCenter(bitmap: Bitmap, ratio: Float): Bitmap? {
    val w = (bitmap.width * ratio).toInt()
    val h = (bitmap.height * ratio).toInt()
    if (w <= 0 || h <= 0) return null
    val x = ((bitmap.width - w) / 2).coerceAtLeast(0)
    val y = ((bitmap.height - h) / 2).coerceAtLeast(0)
    return runCatching { Bitmap.createBitmap(bitmap, x, y, w, h) }.getOrNull()
}

private fun scaleDownIfNeeded(bitmap: Bitmap): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= MAX_DECODE_DIMEN) return bitmap
    val scale = MAX_DECODE_DIMEN.toFloat() / longest
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return runCatching { Bitmap.createScaledBitmap(bitmap, w, h, true) }.getOrDefault(bitmap)
}

// ===== 重绘（保证可扫） =====

/**
 * 用 ZXing 按解码出的内容重新生成条码图。
 *
 * 关键：ZXing 的 1D 编码器按**整数倍**放大条宽并在左右留静区，
 * 所以**天然是横向等比**的 —— 这正是「不会因为拉伸而扫不出」的保证。
 * 纵向高度可任意给（条是竖直的），所以「铺满背景」是安全的。
 *
 * @param widthPx  期望宽度（像素）；实际宽度 ≥ 编码器算出的最小宽度
 * @param heightPx 期望高度（像素）
 */
fun renderBarcode(
    payload: String,
    symbology: BarcodeSymbology,
    widthPx: Int,
    heightPx: Int
): Bitmap? {
    if (payload.isBlank()) return null
    if (widthPx <= 0 || heightPx <= 0) return null
    return try {
        val matrix = MultiFormatWriter().encode(
            payload,
            symbology.zxingFormat,
            widthPx,
            heightPx
        )
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[i++] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    } catch (e: Exception) {
        Log.w(TAG, "render failed: ${e.message}")
        null
    }
}

/** 该条码内容能否用指定符号体系编码（用于界面即时校验，如 EAN-13 必须 12~13 位数字）。 */
fun canEncodeBarcode(payload: String, symbology: BarcodeSymbology): Boolean =
    renderBarcode(payload, symbology, 200, 60) != null
