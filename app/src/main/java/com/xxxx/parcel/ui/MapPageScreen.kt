package com.xxxx.parcel.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xxxx.parcel.ui.components.BarcodeBottomCard
import com.xxxx.parcel.ui.components.BarcodeFullScreenDialog
import com.xxxx.parcel.ui.components.RouteMiniMap
import com.xxxx.parcel.util.GuideDetail
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.RouteStop
import com.xxxx.parcel.util.VenueGuide
import com.xxxx.parcel.util.completedMarkersOf
import com.xxxx.parcel.util.effectiveCompartmentNumber
import com.xxxx.parcel.util.getBarcodePayload
import com.xxxx.parcel.util.getGuideDetail
import com.xxxx.parcel.util.getGuideMapView
import com.xxxx.parcel.util.getRouteOptions
import com.xxxx.parcel.util.groupRouteStops
import com.xxxx.parcel.util.hasBarcodeOriginalImage
import com.xxxx.parcel.util.lastCheckoutOrigin
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.viewmodel.ParcelViewModel

/**
 * **独立地图页**（用户 2026-10-01 提的结构）：
 *
 * ```
 * ┌───────────────┐
 * │ 当前要取的取件码 │  ← 顶部浮窗（含上一站/下一站与「怎么走」）
 * ├───────────────┤
 * │      地图       │  ← 中间，占满剩余（货架旁直接写取件码）
 * ├───────────────┤
 * │      条码       │  ← 底部（与首页底部浮窗同一套）
 * └───────────────┘
 * ```
 *
 * 入口：首页右上角菜单「地图取件」——由「提示与地图（试用）」里的开关控制显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPageScreen(
    context: Context,
    viewModel: ParcelViewModel,
    navController: NavController,
) {
    val successData by viewModel.successSmsData.collectAsState()
    val pending = remember(successData) {
        successData.filter {
            !it.isCompleted && effectiveCompartmentNumber(it.compartmentNumber, it.code).isNotBlank()
        }
    }
    val options = remember { getRouteOptions(context) }
    // 刚取完的那一件 ⇒ 从现场接着走（用户 2026-10-01）
    val checkoutOrigin = remember(pending, options) { lastCheckoutOrigin(context, options) }
    val route = remember(pending, options, checkoutOrigin) {
        planPickupRoute(
            rawCodes = pending.map { effectiveCompartmentNumber(it.compartmentNumber, it.code) },
            options = options,
            startCell = checkoutOrigin?.cell,
            startLabel = checkoutOrigin?.label ?: "入口闸机",
        )
    }
    val detail = getGuideDetail(context)
    val mapView = getGuideMapView(context)
    val byCode = remember(pending) { pending.associateBy { it.code } }
    var currentStop by remember(route) { mutableIntStateOf(0) }
    var barcodeFull by remember { mutableStateOf(false) }
    val hasBarcode = remember { getBarcodePayload(context) != null || hasBarcodeOriginalImage(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("地图取件") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { padding ->
        if (barcodeFull) {
            BarcodeFullScreenDialog(context = context, onDismiss = { barcodeFull = false })
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── 顶部浮窗：当前需要取件的取件码
            CurrentStopCard(
                route = route,
                currentStop = currentStop,
                detail = detail,
                addressOf = { code -> byCode[code]?.address.orEmpty() },
                onStep = { currentStop = it },
            )

            // ── 中间：地图
            if (route.stops.isNotEmpty()) {
                RouteMiniMap(
                    route = route,
                    currentStop = currentStop,
                    detail = detail,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    initialView = mapView,
                    onCurrentStopChange = { currentStop = it },
                    showStopCodes = true,
                    completedMarkers = completedMarkersOf(checkoutOrigin),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有可规划的取件码：需要「快递站」页里有带「货格号」的未取件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // ── 底部：条码
            if (hasBarcode) {
                BarcodeBottomCard(
                    context = context,
                    isSeniorMode = false,
                    heightDp = 118.dp,
                    onDrag = {},
                    onDragStart = {},
                    onDragEnd = {},
                    onOpenSettings = { navController.navigate("barcode") },
                    onTap = { barcodeFull = true },
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        "还没导入快递中心条码 —— 点这里去导入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    )
                }
            }
        }
    }
}

/** 顶部浮窗：大号取件码 + 地址 + 本段距离，带上一站/下一站。
 *
 * 用户 2026-10-01：**同货架的多件合并显示**（标题同时写出来，如「S3-2-2628 · S3-3-7606」），
 * 并且**去掉紫色「怎么走」块** —— 下面地图上已经有了。
 */
@Composable
private fun CurrentStopCard(
    route: PickupRoute,
    currentStop: Int,
    detail: GuideDetail,
    addressOf: (String) -> String,
    onStep: (Int) -> Unit,
) {
    val stops = route.stops
    val idx = if (stops.isEmpty()) 0 else currentStop.coerceIn(0, stops.size - 1)
    val groups = remember(route) { groupRouteStops(route) }
    val group = groups.firstOrNull { idx in it.indexes } ?: groups.firstOrNull()
    val groupIndexes = group?.indexes ?: listOf(idx)
    val first = groupIndexes.first()
    val last = groupIndexes.last()
    val leg = route.legs.getOrNull(idx)
    val title = groupIndexes.mapNotNull { i ->
        when (val s = stops.getOrNull(i)) {
            is RouteStop.Pickup -> s.code.toString()
            RouteStop.SfCheckout -> "顺丰出库（顺丰专用闸机）"
            is RouteStop.Exit -> "出站：${s.kind.label}"
            null -> null
        }
    }.joinToString(" · ").ifBlank { "—" }
    val position = if (groupIndexes.size > 1) "当前 ${first + 1}-${last + 1}/${stops.size}" else "当前 ${idx + 1}/${stops.size}"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        position,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onStep(idx - 1) }, enabled = idx > 0) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一站")
                }
                IconButton(onClick = { onStep(idx + 1) }, enabled = idx < stops.size - 1) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一站")
                }
            }
            val address = stops.getOrNull(idx)?.let { s ->
                (s as? RouteStop.Pickup)?.let { addressOf(it.code.toString()) }
            }.orEmpty()
            if (address.isNotBlank()) {
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (leg != null) {
                Text(
                    "本段 ${fmtTiles(leg.tiles)} 格 · 全程 ${fmtTiles(route.totalTiles)} 格" +
                        if (groupIndexes.size > 1) " · 这 ${groupIndexes.size} 件在同一货架" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
