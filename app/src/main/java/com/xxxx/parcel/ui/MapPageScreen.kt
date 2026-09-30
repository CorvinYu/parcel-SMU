package com.xxxx.parcel.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xxxx.parcel.ui.components.BarcodeBottomCard
import com.xxxx.parcel.ui.components.BarcodeFullScreenDialog
import com.xxxx.parcel.ui.components.RouteMiniMap
import com.xxxx.parcel.ui.components.TripStopSection
import com.xxxx.parcel.ui.components.rememberTripView
import com.xxxx.parcel.util.clearSfCheckoutDone
import com.xxxx.parcel.util.completedMarkersOf
import com.xxxx.parcel.util.countSfCheckouts
import com.xxxx.parcel.util.getBarcodePayload
import com.xxxx.parcel.util.getGuideDetail
import com.xxxx.parcel.util.getGuideMapView
import com.xxxx.parcel.util.getMapBarcodeHeightDp
import com.xxxx.parcel.util.getRouteOptions
import com.xxxx.parcel.util.hasBarcodeOriginalImage
import com.xxxx.parcel.util.isSfCheckoutCountEnabled
import com.xxxx.parcel.util.isSfCheckoutDone
import com.xxxx.parcel.util.lastCheckoutOrigin
import com.xxxx.parcel.util.markSfCheckoutDone
import com.xxxx.parcel.util.recentCheckoutEntries
import com.xxxx.parcel.util.saveMapBarcodeHeightDp
import com.xxxx.parcel.viewmodel.ParcelViewModel
import kotlin.math.roundToInt

/**
 * **独立地图页**（用户 2026-10-01 提的结构）：
 *
 * ```
 * ┌───────────────┐
 * │ 当前取件码 + 序列 │  ← 顶部（与首页全屏地图共用 TripStopSection）
 * ├───────────────┤
 * │      地图       │  ← 中间，占满剩余（货架旁直接写取件码）
 * ├───────────────┤
 * │      条码       │  ← 底部（与首页底部浮窗同一套）
 * └───────────────┘
 * ```
 *
 * 用户 2026-10-01：这一整块**也搬到了首页地图的全屏页** ⇒ 逻辑抽进
 * [TripStopSection] + [rememberTripView]（整段行程冻结、分母不变、已取留原位）。
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
    // 顺丰是否已出库（用户点过顺丰出库卡）⇒ 整段行程里不再有出库节点；tick 用于点击后立刻重算
    var sfDoneTick by remember { mutableIntStateOf(0) }
    val sfDone = remember(successData, sfDoneTick) { isSfCheckoutDone(context) }
    val trip = rememberTripView(successData, options, sfDone)
    // 「N 件待出库」提醒（菜单开关，默认开）
    val sfCountEnabled = isSfCheckoutCountEnabled(context)
    val sfPendingCount = remember(successData, sfDoneTick) {
        countSfCheckouts(recentCheckoutEntries(context))
    }
    val detail = getGuideDetail(context)
    val mapView = getGuideMapView(context)
    // 当前件在序列里的位置（null = 还没选过 ⇒ 第一件未取的）；允许停在已取的那一件上翻看
    var currentPickup by remember { mutableStateOf<Int?>(null) }
    val cur = trip.clampCurrent(currentPickup)
    // 🔴 全取完之后要跳到「前往出口」那一站（用户 2026-10-01）
    val currentStopIndex = trip.focusStopIndex(cur)
    // 刚取完的那一件 ⇒ 图上留个灰 ✓
    val checkoutOrigin = remember(successData, options) { lastCheckoutOrigin(context, options) }
    var barcodeFull by remember { mutableStateOf(false) }
    // 地图页底部条码高度：可拖动、独立持久化（与首页互不影响）。用 Float 保存避免取整丢位移。
    var barcodeHeightDp by remember { mutableFloatStateOf(getMapBarcodeHeightDp(context).toFloat()) }
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
            // ── 顶部：当前取件码（点击标记已取 / 再点恢复）+ 整段序列 + **顺丰出库卡 + 出站卡**
            TripStopSection(
                context = context,
                viewModel = viewModel,
                view = trip,
                current = currentPickup,
                onCurrentChange = { currentPickup = it },
                sfCheckedOut = sfDone,
                onToggleSfDone = {
                    if (sfDone) clearSfCheckoutDone(context) else markSfCheckoutDone(context)
                    sfDoneTick += 1
                },
                showSfCount = sfCountEnabled,
                sfPendingCount = sfPendingCount,
                onShowBarcode = { barcodeFull = true },
            )

            // ── 中间：地图
            if (trip.route.stops.isNotEmpty()) {
                RouteMiniMap(
                    route = trip.route,
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
                        trip.pickups.indexOfFirst { it.first == stopIdx }
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
                        "还没有可规划的取件码：需要「快递站」页里有带「货格号」的取件码。",
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
                        barcodeHeightDp = (barcodeHeightDp - delta.value).coerceIn(70f, 300f)
                    },
                    onDragStart = {},
                    onDragEnd = { saveMapBarcodeHeightDp(context, barcodeHeightDp.roundToInt()) },
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
