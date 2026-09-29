package com.xxxx.parcel.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 微信快递中心「进出条码」的 Android 侧适配：图片加载、Bitmap ↔ 像素、原图存取、设置持久化。
 *
 * **编解码算法本身在 `BarcodeCodec.kt`**（纯 Kotlin、无 Android 依赖、可 JVM 单元测试）。
 *
 * ## 两种出示方式
 *
 * - **原图模式（默认）**：直接把截图里裁出的条码原图存下来出示。
 *   不解码、不重编码 ⇒ **内容 100% 保真**。
 * - **重绘模式**：解码出内容后用 ZXing 重新生成清晰条码。
 *   优点是可以任意放大、无 JPEG 噪点；代价是依赖解码正确性。
 *
 * 之所以默认原图：实测用户的微信专属码截图时发现，条码**实际编码的内容**（11 字符）
 * 与页面上印的明文（8 字符）**并不一致**。这种情况下重绘等于把不确定的东西当真理。
 */
private const val PREFS_NAME = "parcel_prefs"
private const val KEY_PAYLOAD = "barcode_payload"
private const val KEY_SYMBOLOGY = "barcode_symbology"
private const val KEY_STRIP = "barcode_strip_enabled"
private const val KEY_BOTTOM = "barcode_bottom_enabled"
/** 旧「底部填充」开关：已与浮窗合并，此键只用于读取旧设置做迁移 */
private const val KEY_BOTTOM_FILL = "barcode_bottom_fill_enabled"
private const val KEY_BOTTOM_HEIGHT = "barcode_bottom_height_dp"
private const val KEY_BACKGROUND = "barcode_background_enabled"
private const val KEY_USE_ORIGINAL = "barcode_use_original"
private const val KEY_UPDATED_AT = "barcode_updated_at"
private const val TAG = "ParcelBarcode"

/** 识别前先把超大截图缩到这个量级，避免 OOM。 */
private const val MAX_DECODE_DIMEN = 3200

private const val ORIGINAL_IMAGE_FILE = "barcode_original.png"

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

/**
 * 底部浮窗（**「浮窗」与「填充」已合并：现在只有一个底部浮窗**）。
 *
 * 高度由用户在首页**拖动顶部把手**调节（[getBarcodeBottomHeightDp]），
 * 列表内容占满屏幕时自动让位缩到最小高度。
 *
 * 兼容旧设置：以前「底部浮窗」或「底部填充」任一是开的，都算这个浮窗开着。
 */
fun isBarcodeBottomEnabled(context: Context): Boolean {
    val prefs = barcodePrefs(context)
    return prefs.getBoolean(KEY_BOTTOM, false) || prefs.getBoolean(KEY_BOTTOM_FILL, false)
}

fun saveBarcodeBottomEnabled(context: Context, enabled: Boolean) {
    barcodePrefs(context).edit().putBoolean(KEY_BOTTOM, enabled).apply()
}

/** 底部浮窗高度（dp）：用户拖动后的设定值。 */
fun getBarcodeBottomHeightDp(context: Context): Int =
    barcodePrefs(context).getInt(KEY_BOTTOM_HEIGHT, DEFAULT_BOTTOM_HEIGHT_DP)

fun saveBarcodeBottomHeightDp(context: Context, heightDp: Int) {
    barcodePrefs(context).edit().putInt(KEY_BOTTOM_HEIGHT, heightDp).apply()
}

/** 默认高度：普通模式 96dp，老人模式（字大）128dp。 */
const val DEFAULT_BOTTOM_HEIGHT_DP = 96
const val DEFAULT_BOTTOM_HEIGHT_SENIOR_DP = 128

// 「铺满首页背景」功能已按用户要求整条删除（含其 prefs 键与读写函数）。

/**
 * 原图模式：直接用截图里裁出的条码原图，不做解码重编码 ⇒ 内容 100% 保真。
 *
 * **默认关闭**：默认仍是「解码后重绘」（这是已验证可用的行为，条码更干净）。
 * 只有当驿站扫码枪不认重绘出来的条码时，才建议打开原图模式作为兜底。
 */
fun isBarcodeOriginalPreferred(context: Context): Boolean =
    barcodePrefs(context).getBoolean(KEY_USE_ORIGINAL, false)

fun saveBarcodeOriginalPreferred(context: Context, value: Boolean) {
    barcodePrefs(context).edit().putBoolean(KEY_USE_ORIGINAL, value).apply()
}

// ===== 原图存取 =====

fun barcodeOriginalImageFile(context: Context): File = File(context.filesDir, ORIGINAL_IMAGE_FILE)

fun hasBarcodeOriginalImage(context: Context): Boolean = barcodeOriginalImageFile(context).isFile

fun saveBarcodeOriginalImage(context: Context, image: PixelImageResult): Boolean {
    return try {
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        FileOutputStream(barcodeOriginalImageFile(context)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        true
    } catch (e: Exception) {
        Log.w(TAG, "save original barcode failed: ${e.message}")
        false
    }
}

fun clearBarcodeOriginalImage(context: Context) {
    runCatching { barcodeOriginalImageFile(context).delete() }
}

/**
 * 统一的取图入口：按当前模式返回条码位图。
 *
 * 原图模式优先用裁出来的原图（内容保真）；没有原图或用户选择重绘时，才用解码内容重新生成。
 */
fun loadBarcodeBitmap(context: Context, widthPx: Int, heightPx: Int): Bitmap? {
    if (widthPx <= 0 || heightPx <= 0) return null

    if (isBarcodeOriginalPreferred(context)) {
        val file = barcodeOriginalImageFile(context)
        if (file.isFile) {
            decodeImageFile(file)?.let { return it }
        }
    }
    val payload = getBarcodePayload(context) ?: return null
    return renderBarcode(payload, getBarcodeSymbology(context), widthPx, heightPx)
}

private fun decodeImageFile(file: File): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longest / sampleSize > MAX_DECODE_DIMEN) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(file.absolutePath, options)
    } catch (e: Exception) {
        Log.w(TAG, "decode original barcode failed: ${e.message}")
        null
    }
}

// ===== 从截图导入 =====

/** 一次导入的结果：解出的内容 + 是否成功保存了条码原图。 */
data class BarcodeImportResult(val payload: String?, val originalSaved: Boolean)

/**
 * 导入截图：**同时**解出内容并裁出条码原图。
 * 原图用于「原图模式」保真出示，内容用于界面显示与人工核对。
 */
fun importBarcodeFromUri(context: Context, uri: Uri): BarcodeImportResult {
    val bitmap = loadBitmapFromUri(context, uri) ?: return BarcodeImportResult(null, false)
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return BarcodeImportResult(null, false)

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val payload = decodeBarcodeFromScreenshot(pixels, width, height)
    val extracted = extractBarcodeImage(pixels, width, height)
    val saved = extracted != null && saveBarcodeOriginalImage(context, extracted)
    return BarcodeImportResult(payload, saved)
}

/** 从相册/截图 Uri 中解出条码内容（不保存原图）。 */
fun decodeBarcodeFromUri(context: Context, uri: Uri): String? = importBarcodeFromUri(context, uri).payload

fun decodeBarcodeFromBitmap(bitmap: Bitmap): String? {
    val scaled = scaleDownIfNeeded(bitmap)
    val width = scaled.width
    val height = scaled.height
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    scaled.getPixels(pixels, 0, width, 0, 0, width, height)
    return decodeBarcodeFromScreenshot(pixels, width, height)
}

// ===== 重绘（交给纯逻辑核心，保证横向等比）=====

fun renderBarcode(
    payload: String,
    symbology: BarcodeSymbology,
    widthPx: Int,
    heightPx: Int,
): Bitmap? {
    val result = renderBarcodePixels(payload, symbology, widthPx, heightPx) ?: return null
    return try {
        Bitmap.createBitmap(result.pixels, result.width, result.height, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        Log.w(TAG, "bitmap from pixels failed: ${e.message}")
        null
    }
}

// ===== 图片加载 =====

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_DECODE_DIMEN) {
                    var sampleSize = 1
                    while (longest / sampleSize > MAX_DECODE_DIMEN) sampleSize *= 2
                    decoder.setTargetSampleSize(sampleSize)
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri), null, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                var sampleSize = 1
                while (longest / sampleSize > MAX_DECODE_DIMEN) sampleSize *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeStream(input, null, options)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "load image failed: ${e.message}")
        null
    }
}

private fun scaleDownIfNeeded(bitmap: Bitmap): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= MAX_DECODE_DIMEN) return bitmap
    val scale = MAX_DECODE_DIMEN.toFloat() / longest
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return runCatching { Bitmap.createScaledBitmap(bitmap, w, h, true) }.getOrDefault(bitmap)
}
