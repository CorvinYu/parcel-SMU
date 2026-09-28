package com.xxxx.parcel.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * 微信快递中心「进出条码」的 Android 侧适配：图片加载、Bitmap ↔ 像素、设置持久化。
 *
 * **编解码算法本身不在这里**，而在 `BarcodeCodec.kt`（纯 Kotlin、无 Android 依赖、可 JVM 单元测试）。
 * 这么拆是为了让「截图能不能认出条码」「铺满全屏后还能不能扫」这两件事有真凭实据。
 *
 * 背景与约束（详见项目 NOTES.md）：
 * - 该条码是**静态**的一维 Code128，截图一次可长期使用。
 * - 一维条码只能**横向等比**缩放；纵向可任意拉伸。
 * - 用 ZXing 而非 ML Kit：前者纯 Java、完全离线，符合本 app「不联网」定位。
 */
private const val PREFS_NAME = "parcel_prefs"
private const val KEY_PAYLOAD = "barcode_payload"
private const val KEY_SYMBOLOGY = "barcode_symbology"
private const val KEY_STRIP = "barcode_strip_enabled"
private const val KEY_BOTTOM = "barcode_bottom_enabled"
private const val KEY_BACKGROUND = "barcode_background_enabled"
private const val KEY_UPDATED_AT = "barcode_updated_at"
private const val TAG = "ParcelBarcode"

/** 识别前先把超大截图缩到这个量级，避免 OOM 与无谓的耗时。 */
private const val MAX_DECODE_DIMEN = 2000

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

/** 底部浮窗：条码浮在列表下方，列表短时正好占住底部空白 */
fun isBarcodeBottomEnabled(context: Context): Boolean =
    barcodePrefs(context).getBoolean(KEY_BOTTOM, false)

fun saveBarcodeBottomEnabled(context: Context, enabled: Boolean) {
    barcodePrefs(context).edit().putBoolean(KEY_BOTTOM, enabled).apply()
}

/** 铺满首页背景 */
fun isBarcodeBackgroundEnabled(context: Context): Boolean =
    barcodePrefs(context).getBoolean(KEY_BACKGROUND, false)

fun saveBarcodeBackgroundEnabled(context: Context, enabled: Boolean) {
    barcodePrefs(context).edit().putBoolean(KEY_BACKGROUND, enabled).apply()
}

// ===== 从截图识别 =====

/** 从相册/截图 Uri 中解出条码内容（识别策略见 `decodeBarcodeFromScreenshot`）。 */
fun decodeBarcodeFromUri(context: Context, uri: Uri): String? {
    val bitmap = loadBitmapFromUri(context, uri) ?: return null
    return decodeBarcodeFromBitmap(bitmap)
}

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

private fun scaleDownIfNeeded(bitmap: Bitmap): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= MAX_DECODE_DIMEN) return bitmap
    val scale = MAX_DECODE_DIMEN.toFloat() / longest
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return runCatching { Bitmap.createScaledBitmap(bitmap, w, h, true) }.getOrDefault(bitmap)
}
