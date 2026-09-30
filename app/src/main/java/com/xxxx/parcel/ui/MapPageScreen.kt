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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import com.xxxx.parcel.util.hasBarcodeOriginalImage
import com.xxxx.parcel.util.lastCheckoutOrigin
import com.xxxx.parcel.util.parseCompartmentCode
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.removeCompletedId
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
    val options = remember { getRouteOptions(context) }
    // 🔴 **整段行程**（含**已经取掉的**）一起规划，并且**冻结**：标记已取**不会**改变这条序列、
    //    也不会改变分母（用户 2026-10-01：点一下应当从「当前 1/50」变成「当前 2/50」，
    //    不是变成「当前 1/49」）。
    //    所以这里用「全部快递站取件码（含已取）」而不是「只剩未取」，也不把起点设成「刚取完的那件」
    //    （否则每取一件，整条顺序与位置都会重排）。
    val tripCodes = remember(successData) {
        successData
            .map { effectiveCompartmentNumber(it.compartmentNumber, it.code) }
            .filter { it.isNotBlank() }
            .distinct()
    }
    val route = remember(tripCodes, options) {
        planPickupRoute(rawCodes = tripCodes, options = options)
    }
    // 已取件（按有效货格号）；同一件在短信列表里的完成标记就是唯一事实来源
    val completedCodes = remember(successData) {
        successData
            .filter { it.isCompleted }
            .map { effectiveCompartmentNumber(it.compartmentNumber, it.code) }
            .toSet()
    }
    // 序列里的取件站（下标 → 该站在 route.stops 里的位置），顺序与地图/路线完全一致
    val tripPickups: List<Pair<Int, String>> = remember(route) {
        route.stops.mapIndexedNotNull { i, s ->
            (s as? RouteStop.Pickup)?.let { i to it.code.toString() }
        }
    }
    val detail = getGuideDetail(context)
    val mapView = getGuideMapView(context)
    // 🔴 必须按**有效货格号**建索引：短信里的取件码未必等于货格号（例如取件码是 2628、货格号是 S3-2-2628），
    //    之前用 `it.code` 建映射 ⇒ 地图页点「已取件」匹配不到，点了没反应（用户 2026-10-01 反馈）。
    val byCompartment = remember(successData) {
        buildMap {
            successData.forEach { s ->
                parseCompartmentCode(effectiveCompartmentNumber(s.compartmentNumber, s.code))?.let { put(it, s) }
            }
        }
    }
    // 当前件在**序列里的位置**（0 基）。null = 还没选过 ⇒ 落在第一件未取的。
    // 🔴 用户手动翻看时**允许停在已取的那一件上**（用户 2026-10-01：「如果我需要的话，我会自己翻回来看」），
    //    所以这里不把「已取」当成无效下标；真正的「自动跳下一格」由 markCompleted 里显式前进。
    var currentPickup by remember { mutableStateOf<Int?>(null) }
    val firstPending = tripPickups.indexOfFirst { it.second !in completedCodes }
    val cur = currentPickup?.takeIf { it in tripPickups.indices }
        ?: firstPending.takeIf { it >= 0 }
        ?: tripPickups.lastIndex.coerceAtLeast(0)
    // 地图要高亮的那一站（route.stops 的下标）：全取完时停在最后一站，别越界
    val currentStopIndex = when {
        cur in tripPickups.indices -> tripPickups[cur].first
        tripPickups.isNotEmpty() -> tripPickups.last().first
        else -> 0
    }
    val checkoutOrigin = remember(successData, options) { lastCheckoutOrigin(context, options) }
    var barcodeFull by remember { mutableStateOf(false) }
    // 底部条码高度：可拖动调节（顶部卡固定 / 地图 weight(1f) 吃剩余 / 条码高度可拖）。
    // 🔴 之前写 `+ delta.value` 符号反了：向上拖 delta 为负，相加反而变矮、撞 70 下限 ⇒ 像没反应（用户 2026-10-01）。
    //    改成 `- delta.value`：向上拖 ⇒ 条码变高、地图让位。高度持久化（与首页独立）。
    var barcodeHeightDp by remember { mutableIntStateOf(getMapBarcodeHeightDp(context)) }
    val hasBarcode = remember { getBarcodePayload(context) != null || hasBarcodeOriginalImage(context) }

    // 点击当前件：**未取的标记为已取**（同货架的一起），**已取的再点一下恢复为未取件**
    // （用户 2026-10-01 明确要求可来回切换）
    val toggleCompleted: (String) -> Unit = { code ->
        val parsed = parseCompartmentCode(code)
        val target = parsed?.let { byCompartment[it] }
        when {
            parsed == null || target == null -> Unit
            // 恢复未取件：只恢复**这一件**（同货架其他件是先前单独取的，不替他决定）
            target.isCompleted -> removeCompletedId(context, viewModel, target.sms, target.code)
            else -> {
                val shelf = "${parsed.rowLetter.uppercaseChar()}${parsed.shelfNumber}"
                val targets = byCompartment.filterKeys { k ->
                    k.zone == parsed.zone && "${k.rowLetter.uppercaseChar()}${k.shelfNumber}" == shelf
                }.values.filterNot { it.isCompleted }.distinct()
                if (targets.isNotEmpty()) {
                    addCompletedIds(context, viewModel, targets.map { it.sms }, targets.map { it.code })
                    // **显式前进一格**（用户 2026-10-01：点一下应当从「当前 1/50」变成「当前 2/50」）：
                    // 从当前位置往后找第一件还没取的；后面没有了就从头找（都取完了就停在原地）。
                    val nowDone = completedCodes + targets.map { t ->
                        parseCompartmentCode(effectiveCompartmentNumber(t.compartmentNumber, t.code))
                            ?.toString() ?: t.code
                    }
                    val from = if (cur in tripPickups.indices) cur else 0
                    val next = ((from + 1) until tripPickups.size).firstOrNull { tripPickups[it].second !in nowDone }
                        ?: (0 until tripPickups.size).firstOrNull { tripPickups[it].second !in nowDone }
                    currentPickup = next ?: from
                }
            }
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
            BarcodeFullScreenDialog(
                context = context,
                onDismiss = { barcodeFull = false },
                onOpenSettings = {
                    barcodeFull = false
                    navController.navigate("barcode")
                },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── 顶部卡：当前件（分母 = **整段行程的件数**，点一下自动前进一格）
            TripStopCard(
                pickups = tripPickups,
                current = cur,
                completed = completedCodes,
                allDone = firstPending < 0,
                addressOf = { code -> parseCompartmentCode(code)?.let { byCompartment[it]?.address }.orEmpty() },
                legTiles = route.legs.getOrNull(currentStopIndex)?.tiles,
                totalTiles = route.totalTiles,
                onJump = { currentPickup = it },
                onToggleCompleted = toggleCompleted,
            )

            // ── 整段序列（横向可滑）：已取的**留在原位**、变灰 + 删除线；点任意一格可翻回去看
            TripStrip(
                pickups = tripPickups,
                current = cur,
                completed = completedCodes,
                onJump = { currentPickup = it },
            )

            // ── 中间：地图
            if (route.stops.isNotEmpty()) {
                RouteMiniMap(
                    route = route,
                    currentStop = currentStopIndex,
                    detail = detail,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    initialView = mapView,
                    // 用户 2026-10-01：地图取件页**不显示**窗格里那条紫色「怎么走」提示
                    //（顶部取件码卡已经把该说的话说完了，重复）
                    showHintPill = false,
                    // 🔴 地图上的 ◀ ▶ 传的是**站在 route.stops 里的下标**（含顺丰出库/出站这种非取件站），
                    //    而这里存的是**件在序列里的下标** ⇒ 必须反过来映射一次，否则一按就跳错件。
                    onCurrentStopChange = { stopIdx ->
                        tripPickups.indexOfFirst { it.first == stopIdx }
                            .takeIf { it >= 0 }
                            ?.let { currentPickup = it }
                    },
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

/**
 * 顶部卡：**当前件**（分母 = 整段行程的件数，点一下前进一格）。
 *
 * 用户 2026-10-01 的三条要求就是它存在的理由：
 * 1. 位置写「当前 i/N」，**N 是整段行程的件数（分母不变）**：点一下从「当前 1/50」变「当前 2/50」，
 *    而不是「当前 1/49」（被取走的那件不参与分母）；
 * 2. 点一下 = 把当前这件（**同货架一起**）标记为已取，然后自动前进一格；
 * 3. 已取的件**留在原位**、灰色 + 删除线（见 [TripStrip]），需要时自己翻回去看。
 */
@Composable
private fun TripStopCard(
    pickups: List<Pair<Int, String>>,
    current: Int,
    completed: Set<String>,
    allDone: Boolean,
    addressOf: (String) -> String,
    legTiles: Double?,
    totalTiles: Double,
    onJump: (Int) -> Unit,
    onToggleCompleted: (String) -> Unit,
) {
    if (pickups.isEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 4.dp),
            shape = Corners.cardShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("还没有可规划的取件码", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "需要「快递站」页里有带「货格号」的取件码（已经取掉的也会留在序列里）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }
    val idx = current.coerceIn(0, pickups.size - 1)
    val code = pickups[idx].second
    val isDone = code in completed
    val address = addressOf(code)

    // 左右滑 = 上一件 / 下一件（用户 2026-10-01）；点一下 = 已取件
    var dragX by remember(idx) { mutableFloatStateOf(0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 4.dp)
            .pointerInput(idx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragX <= -60f -> onJump(idx + 1)
                            dragX >= 60f -> onJump(idx - 1)
                        }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { _, delta -> dragX += delta },
                )
            }
            // 点击切换：未取 ⇒ 已取；已取 ⇒ 恢复未取（用户 2026-10-01）
            .clickable { onToggleCompleted(code) },
        shape = Corners.cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            // 分母永远是整段行程的件数（用户要求：1/50 → 2/50，不是 1/49）
                            "当前 ${idx + 1}/${pickups.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (isDone) {
                            Text(
                                "已取",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .clip(Corners.chipShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = code,
                        // 已取的：灰色 + 删除线（与首页已取件的样式一致）
                        textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (isDone) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onJump(idx - 1) }, enabled = idx > 0) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一件")
                }
                IconButton(onClick = { onJump(idx + 1) }, enabled = idx < pickups.size - 1) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一件")
                }
            }
            if (address.isNotBlank()) {
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (legTiles != null) {
                Text(
                    "本段 ${fmtTiles(legTiles)} 格 · 全程 ${fmtTiles(totalTiles)} 格",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                when {
                    allDone -> "整段都取完了 · 按「出站」指引离开"
                    isDone -> "再次点击可恢复为未取件"
                    // 用户 2026-10-01：不要用「=」
                    else -> "点击标记已取 · 左右滑切换 · 下方可翻看整段"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * **整段序列**（横向可滑）：`序号 · 取件码`。
 *
 * 用户 2026-10-01：不要另开一条「已取」列表 —— 已取的**留在它原来的位置**、变成**灰色 + 删除线**，
 * 需要时自己往左翻回去看。所以这里可滚动、可点任意一格跳过去。
 */
@Composable
private fun TripStrip(
    pickups: List<Pair<Int, String>>,
    current: Int,
    completed: Set<String>,
    onJump: (Int) -> Unit,
) {
    if (pickups.isEmpty()) return
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // 当前格自动滚进视野（每格宽度按经验值估，只为「别让当前那格滑出屏幕」）
    LaunchedEffect(current, pickups.size) {
        if (current in pickups.indices) {
            val approx = with(density) { (current * 78).dp.roundToPx() }
            scroll.animateScrollTo(approx.coerceIn(0, scroll.maxValue))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pickups.forEachIndexed { i, pair ->
            val done = pair.second in completed
            val isCur = i == current
            Text(
                text = "${i + 1} · ${pair.second}",
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                color = when {
                    isCur -> MaterialTheme.colorScheme.onPrimary
                    done -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .clip(Corners.chipShape)
                    .background(
                        when {
                            isCur -> MaterialTheme.colorScheme.primary
                            done -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .clickable { onJump(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
