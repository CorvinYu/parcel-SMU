package com.xxxx.parcel.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xxxx.parcel.ui.components.BarcodeBottomCard
import com.xxxx.parcel.ui.components.BarcodeFullScreenDialog
import com.xxxx.parcel.ui.components.RouteMiniMap
import com.xxxx.parcel.ui.theme.Corners
import com.xxxx.parcel.util.GuideDetail
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.RouteStop
import com.xxxx.parcel.util.VenueGuide
import com.xxxx.parcel.util.addCompletedIds
import com.xxxx.parcel.util.completedMarkersOf
import com.xxxx.parcel.util.effectiveCompartmentNumber
import com.xxxx.parcel.util.getBarcodePayload
import com.xxxx.parcel.util.getMapBarcodeHeightDp
import com.xxxx.parcel.util.getGuideDetail
import com.xxxx.parcel.util.getGuideMapView
import com.xxxx.parcel.util.getRouteOptions
import com.xxxx.parcel.util.groupRouteStops
import com.xxxx.parcel.util.hasBarcodeOriginalImage
import com.xxxx.parcel.util.lastCheckoutOrigin
import com.xxxx.parcel.util.parseCompartmentCode
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.saveMapBarcodeHeightDp
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
    // 🔴 必须按**有效货格号**建索引：短信里的取件码未必等于货格号（例如取件码是 2628、货格号是 S3-2-2628），
    //    之前用 `it.code` 建映射 ⇒ 地图页点「已取件」匹配不到，点了没反应（用户 2026-10-01 反馈）。
    val byCompartment = remember(pending) {
        buildMap {
            pending.forEach { s ->
                parseCompartmentCode(effectiveCompartmentNumber(s.compartmentNumber, s.code))?.let { put(it, s) }
            }
        }
    }
    // 🔴 不要用 route 作 key：点「已取件」后路线会少一件，若重置为 0 就会跳回第一站；
    //    保持下标不变 ⇒ 被取走的那一组消失后，同一下标正好落在**下一站**（用户 2026-10-01 要的自动跳下一格）
    var currentStop by remember { mutableIntStateOf(0) }
    var barcodeFull by remember { mutableStateOf(false) }
    // 底部条码高度：可拖动调节（顶部卡固定 / 地图 weight(1f) 吃剩余 / 条码高度可拖）。
    // 🔴 之前写 `+ delta.value` 符号反了：向上拖 delta 为负，相加反而变矮、撞 70 下限 ⇒ 像没反应（用户 2026-10-01）。
    //    改成 `- delta.value`：向上拖 ⇒ 条码变高、地图让位。高度持久化（与首页独立）。
    var barcodeHeightDp by remember { mutableIntStateOf(getMapBarcodeHeightDp(context)) }
    val hasBarcode = remember { getBarcodePayload(context) != null || hasBarcodeOriginalImage(context) }
    // 本次会话里已经取掉的（货格号 → 地址）：顶部保留一排**灰色 + 删除线**的「已取」条
    // （用户 2026-10-01：点一下之后不要直接消失，要变成灰色带删除线、和首页一样，然后自动跳到下一格）
    val doneHere = remember { mutableStateListOf<Pair<String, String>>() }

    // 点顶部取件码 = 把这一组（同货架的全部码）标记为已取件
    val markCompleted: (List<String>) -> Unit = { codes ->
        val targets = codes
            .mapNotNull { c -> parseCompartmentCode(c)?.let { byCompartment[it] } }
            .distinct()
        if (targets.isNotEmpty()) {
            addCompletedIds(context, viewModel, targets.map { it.sms }, targets.map { it.code })
            // 记录到「已取」条：用**有效货格号**显示（与卡片标题同一套口径）
            targets.forEach { t ->
                val key = parseCompartmentCode(effectiveCompartmentNumber(t.compartmentNumber, t.code))?.toString()
                    ?: t.code
                doneHere.removeAll { it.first == key }
                doneHere.add(0, key to t.address)
            }
            // 只留最近 8 条，避免把顶部顶爆
            while (doneHere.size > 8) doneHere.removeAt(doneHere.size - 1)
        }
    }

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
                addressOf = { code ->
                    parseCompartmentCode(code)?.let { byCompartment[it]?.address }.orEmpty()
                },
                onStep = { currentStop = it },
                onMarkCompleted = markCompleted,
            )

            // 已取（灰条 + 删除线）：点过的件不会凭空消失，留在这里作为确认
            DoneStrip(doneHere)

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
                    heightDp = barcodeHeightDp.dp,
                    onDrag = { delta ->
                        // 向上拖（delta 为负）⇒ 条码变高、地图让位（与首页方向一致）
                        barcodeHeightDp = (barcodeHeightDp - delta.value).toInt().coerceIn(70, 300)
                    },
                    onDragStart = {},
                    onDragEnd = { saveMapBarcodeHeightDp(context, barcodeHeightDp) },
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
    onMarkCompleted: (List<String>) -> Unit,
) {
    val stops = route.stops
    val idx = if (stops.isEmpty()) 0 else currentStop.coerceIn(0, stops.size - 1)
    val groups = remember(route) { groupRouteStops(route) }
    val group = groups.firstOrNull { idx in it.indexes } ?: groups.firstOrNull()
    val groupIndexes = group?.indexes ?: listOf(idx)
    val first = groupIndexes.first()
    val last = groupIndexes.last()
    val leg = route.legs.getOrNull(idx)
    val groupCodes = groupIndexes.mapNotNull { i -> (stops.getOrNull(i) as? RouteStop.Pickup)?.code?.toString() }
    val title = groupIndexes.mapNotNull { i ->
        when (val s = stops.getOrNull(i)) {
            is RouteStop.Pickup -> s.code.toString()
            RouteStop.SfCheckout -> "顺丰出库（顺丰专用闸机）"
            is RouteStop.Exit -> "出站：${s.kind.label}"
            null -> null
        }
    }.joinToString(" · ").ifBlank { "—" }
    val position = if (groupIndexes.size > 1) "当前 ${first + 1}-${last + 1}/${stops.size}" else "当前 ${idx + 1}/${stops.size}"

    // 左右滑 = 上一站 / 下一站（用户 2026-10-01）；点一下 = 已取件
    var dragX by remember(idx) { mutableFloatStateOf(0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp)
            .pointerInput(idx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragX <= -60f -> onStep(idx + 1)
                            dragX >= 60f -> onStep(idx - 1)
                        }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { _, delta -> dragX += delta },
                )
            }
            .clickable(enabled = groupCodes.isNotEmpty()) { onMarkCompleted(groupCodes) },
        shape = Corners.cardShape,
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
            Text(
                if (groupCodes.isNotEmpty()) "点一下＝已取件 · 左右滑＝换站" else "左右滑＝换站",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 「已取」灰条：地图取件页点过的取件码留在这里（灰色 + **删除线** + ✓），与首页已取件的样式一致。
 *
 * 为什么要有它（用户 2026-10-01）：点一下之后那一条直接消失，看不出「到底点上了没有」；
 * 希望它变成灰色带删除线、然后再自动跳到下一格 —— 当前站由 `currentStop` 自动前进（见 `MapPageScreen`）。
 */
@Composable
private fun DoneStrip(items: List<Pair<String, String>>) {
    if (items.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已取",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        items.forEach { (code, _) ->
            Text(
                text = "✓ $code",
                textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier
                    .clip(Corners.pillShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
