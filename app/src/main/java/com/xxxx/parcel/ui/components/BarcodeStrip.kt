package com.xxxx.parcel.ui.components

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xxxx.parcel.ui.theme.Corners
import com.xxxx.parcel.util.BarcodeSymbology
import com.xxxx.parcel.util.getBarcodePayload
import com.xxxx.parcel.util.getBarcodeSymbology
import com.xxxx.parcel.util.hasBarcodeOriginalImage
import com.xxxx.parcel.util.isBarcodeOriginalPreferred
import com.xxxx.parcel.util.loadBarcodeBitmap
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
    payload: String?,
    symbology: BarcodeSymbology,
    heightDp: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val useOriginal = remember { isBarcodeOriginalPreferred(context) }
        val hasOriginal = remember { hasBarcodeOriginalImage(context) }
        // ⚠️ 这里**只按宽度**渲染一次，高度用固定值，原因是两个实测问题：
        //   1) 高度在做动画 ⇒ 若把它当 key，会**每帧重算整张位图**（百万级像素循环）→ 卡顿；
        //   2) key 一变，produceState 会把值重置为 null ⇒ 动画中间闪出几帧空白"白窗"。
        // 一维条码纵向拉伸不影响识别（条宽不变），所以显示时用 FillBounds 撑满容器即可。
        val renderHeightPx = 300
        val bitmap by produceState<Bitmap?>(null, payload, symbology, widthPx, useOriginal, hasOriginal) {
            value = withContext(Dispatchers.Default) {
                loadBarcodeBitmap(context, widthPx, renderHeightPx)
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
                    text = "条码不可用（内容或码制不匹配）",
                    color = Color(0xFFAA0000),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            } else {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "取件条码",
                    modifier = Modifier.fillMaxSize(),
                    // 一维条码纵向拉伸无害（条宽不变）；二维码必须等比，否则会变形扫不出
                    contentScale = if (symbology == BarcodeSymbology.QR_CODE) {
                        ContentScale.Fit
                    } else {
                        ContentScale.FillBounds
                    },
                )
            }
        }
    }
}

/**
 * 首页顶部常驻条码条：**铺满整行**，点一下进条码设置页（原「全屏出示」已按用户要求删除）。
 */
@Composable
fun BarcodeStrip(
    context: Context,
    isSeniorMode: Boolean,
    onOpenSettings: () -> Unit,
) {
    val payload = getBarcodePayload(context)
    val symbology = getBarcodeSymbology(context)
    // 有原图也算「已设置」——原图模式不依赖解码是否成功
    val hasBarcode = !payload.isNullOrBlank() || hasBarcodeOriginalImage(context)
    val textStyle = if (isSeniorMode) MaterialTheme.typography.headlineSmall
    else MaterialTheme.typography.bodyLarge

    Surface(
        color = Color.White,
        shadowElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenSettings() },
    ) {
        if (payload.isNullOrBlank()) {
            Text(
                text = "尚未设置快递中心条码 · 点这里去设置",
                style = textStyle,
                color = Color(0xFF444444),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        } else {
            BarcodeImage(
                payload = payload,
                symbology = symbology,
                heightDp = if (isSeniorMode) 104 else 72,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 底部条码浮窗（**「浮窗」与「填充」已合并成这一个**）。
 *
 * - 高度由 [heightDp] 决定：用户可在顶部把手上**上下拖动**调节，设置会持久化；
 * - 首页会把「列表内容没占满时剩下的空白」算出来传进来，所以列表短时它占住空白、
 *   列表一长就自动让位（缩到最小高度）——见 `HomeScreen` 里的 `targetBottomHeight`；
 * - 点一下卡片进条码设置页（原「全屏出示」已按用户要求删除）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BarcodeBottomCard(
    context: Context,
    isSeniorMode: Boolean,
    heightDp: Dp,
    /** 拖动过程中上报位移（dp）：向上拖为负值 ⇒ 变高 */
    onDrag: (Dp) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 点一下卡片：全屏出示条码（默认回退到进设置页） */
    onTap: () -> Unit = onOpenSettings,
) {
    val payload = getBarcodePayload(context)
    val symbology = getBarcodeSymbology(context)
    // 有原图也算「已设置」——原图模式不依赖解码是否成功
    val hasBarcode = !payload.isNullOrBlank() || hasBarcodeOriginalImage(context)
    val textStyle = if (isSeniorMode) MaterialTheme.typography.headlineSmall
    else MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    // 扣掉四边外边距（上下各 8dp）＋ 顶部把手与内边距，剩下的高度给条码
    val innerHeightDp = (heightDp.value - 16f - 26f).coerceAtLeast(40f).toInt()

    // 🔴 外边距**绝不能算进高度链路**：把 `padding` 写在 `height` 之前，整块占位会变成
    //    `heightDp + 16dp`；而首页/地图页的地图窗格是 `weight(1f)`，多出来的 16dp 在小屏上会把
    //    地图挤到 0 高（整块不渲染）。⇒ 外边距放**外层 Box**，外层占位仍恰好是 `heightDp`，
    //    内圈白卡 = `heightDp − 16dp`。观感（四边留白 + 四角全圆）不变。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            // 用户 2026-10-01：与上方地图窗格之间的空隙要小一点 ⇒ 上图 4dp、这里 4dp
            .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
    ) {
    Surface(
        color = Color.White,
        shadowElevation = 8.dp,
        // 与地图/提示卡统一：四角全圆 + 四边留白，做成一张真正「浮起来的圆角卡」。
        // 🔴 之前只给了 start/end/bottom 三边，**顶部贴死**上面的地图窗格 ⇒ 白卡的直边像把上面的
        //    胶囊「切」了一刀（用户 2026-10-01 两次提到「很锋利地切割其他胶囊」）。
        shape = Corners.cardShape,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部拖动把手
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(with(density) { dragAmount.y.toDp() })
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .background(Color(0xFFB8C2CC), Corners.pillShape),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .combinedClickable(
                        // 点一下 = **全屏出示**（用户 2026-10-01：这个功能当年是误解，不能删）
                        onClick = { if (hasBarcode) onTap() else onOpenSettings() },
                        // 长按 = 进条码设置页
                        onLongClick = { onOpenSettings() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (!hasBarcode) {
                    Text(
                        text = "尚未设置快递中心条码 · 点这里去设置",
                        style = textStyle,
                        color = Color(0xFF444444),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                } else {
                    BarcodeImage(
                        payload = payload,
                        symbology = symbology,
                        heightDp = innerHeightDp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
    }
}

/**
 * 条码**全屏出示**（用户 2026-10-01：恢复此功能 —— 当年是我误解了，并没有要删）。
 *
 * 整屏白底 + 尽量大的条码 + 屏幕亮度拉满，点任意处退出；退出时恢复原亮度。
 */
@Composable
fun BarcodeFullScreenDialog(
    context: Context,
    onDismiss: () -> Unit,
) {
    val payload = getBarcodePayload(context)
    val symbology = getBarcodeSymbology(context)
    val hasBarcode = !payload.isNullOrBlank() || hasBarcodeOriginalImage(context)
    val activity = context as? Activity

    // 亮屏：进出各设一次，退出恢复
    DisposableEffect(Unit) {
        val window = activity?.window
        val original = window?.attributes?.screenBrightness
        window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = 1f
            w.attributes = lp
        }
        onDispose {
            window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = original ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onDismiss() },
            color = Color.White,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (hasBarcode) {
                    BarcodeImage(
                        payload = payload,
                        symbology = symbology,
                        heightDp = 260,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text("尚未设置快递中心条码", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF333333))
                }
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "点屏幕任意处退出 · 亮度已拉满",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF666666),
                )
            }
        }
    }
}
