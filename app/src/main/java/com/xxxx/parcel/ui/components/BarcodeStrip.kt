package com.xxxx.parcel.ui.components

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xxxx.parcel.util.BarcodeSymbology
import com.xxxx.parcel.util.getBarcodePayload
import com.xxxx.parcel.util.getBarcodeSymbology
import com.xxxx.parcel.util.renderBarcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把条码画成一张图（异步生成，避免在主线程做像素运算）。
 *
 * 注意：宽度就是「可用宽度」的像素值，renderBarcode 内部由 ZXing 按整数倍放大条宽，
 * 所以横向天然等比——不会因为铺满而变形导致扫不出。
 */
@Composable
fun BarcodeImage(
    payload: String,
    symbology: BarcodeSymbology,
    heightDp: Int,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { heightDp.dp.roundToPx() }
        val bitmap by produceState<Bitmap?>(null, payload, symbology, widthPx, heightPx) {
            value = withContext(Dispatchers.Default) {
                renderBarcode(payload, symbology, widthPx, heightPx)
            }
        }
        val image = bitmap
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                Text(
                    text = "条码生成失败（内容或码制不匹配）",
                    color = Color(0xFFAA0000),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "取件条码",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

/** 首页顶部常驻条码条：不用翻页就能出示。 */
@Composable
fun BarcodeStrip(
    context: Context,
    isSeniorMode: Boolean,
    onPresent: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val payload = getBarcodePayload(context)
    val symbology = getBarcodeSymbology(context)
    val textStyle = if (isSeniorMode) MaterialTheme.typography.headlineSmall
    else MaterialTheme.typography.bodyLarge

    Surface(
        color = Color.White,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (payload.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "尚未设置快递中心条码",
                    style = textStyle,
                    color = Color(0xFF444444),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSettings) { Text("去设置", style = textStyle) }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarcodeImage(
                    payload = payload,
                    symbology = symbology,
                    heightDp = if (isSeniorMode) 104 else 68,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onPresent) {
                    Text(if (isSeniorMode) "出示\n条码" else "出示", textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * 「出示模式」全屏浮层：只留条码 + 白色底 + 屏幕亮度拉满，
 * 并盖掉首页卡片（卡片压住条码会导致扫码枪读不到）。
 */
@Composable
fun BarcodePresentationDialog(
    context: Context,
    onDismiss: () -> Unit,
) {
    val payload = getBarcodePayload(context)
    val symbology = getBarcodeSymbology(context)
    val configuration = LocalConfiguration.current

    // 出示期间把屏幕亮度拉到最高，退出时恢复
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val original = window?.attributes?.screenBrightness
        window?.let {
            val attrs = it.attributes
            attrs.screenBrightness = 1f
            it.attributes = attrs
        }
        onDispose {
            window?.let {
                val attrs = it.attributes
                attrs.screenBrightness = original ?: -1f
                it.attributes = attrs
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (payload.isNullOrBlank()) {
                    Text(
                        text = "尚未设置条码内容",
                        fontSize = 20.sp,
                        color = Color(0xFF222222),
                    )
                } else {
                    Text(
                        text = "快递中心通行条码",
                        fontSize = 18.sp,
                        color = Color(0xFF666666),
                    )
                    Spacer(Modifier.height(16.dp))
                    BarcodeImage(
                        payload = payload,
                        symbology = symbology,
                        heightDp = (configuration.screenHeightDp * 0.32f).toInt().coerceAtLeast(160),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = payload,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF222222),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(28.dp))
                TextButton(onClick = onDismiss) {
                    Text("点击任意处关闭", fontSize = 16.sp, color = Color(0xFF666666))
                }
            }
        }
    }
}
