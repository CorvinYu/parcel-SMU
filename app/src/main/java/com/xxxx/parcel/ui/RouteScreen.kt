package com.xxxx.parcel.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.xxxx.parcel.ui.components.RouteMiniMap
import com.xxxx.parcel.util.GuideDetail
import com.xxxx.parcel.util.GuideMapPlacement
import com.xxxx.parcel.util.GuideMapView
import com.xxxx.parcel.util.GuideTextPlacement
import com.xxxx.parcel.util.MAX_EXACT_ITEMS
import com.xxxx.parcel.util.PickupSpot
import com.xxxx.parcel.util.RouteExit
import com.xxxx.parcel.util.RouteLeg
import com.xxxx.parcel.util.RouteOptions
import com.xxxx.parcel.util.RouteStop
import com.xxxx.parcel.util.VenueGuide
import com.xxxx.parcel.util.effectiveCompartmentNumber
import com.xxxx.parcel.util.getGuideDetail
import com.xxxx.parcel.util.getGuideMapPlacement
import com.xxxx.parcel.util.getGuideMapView
import com.xxxx.parcel.util.getGuideTextPlacement
import com.xxxx.parcel.util.getRouteJCells
import com.xxxx.parcel.util.isMapPageEnabled
import com.xxxx.parcel.util.parseCompartmentCode
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.saveGuideDetail
import com.xxxx.parcel.util.saveGuideMapPlacement
import com.xxxx.parcel.util.saveGuideMapView
import com.xxxx.parcel.util.saveGuideTextPlacement
import com.xxxx.parcel.util.saveMapPageEnabled
import com.xxxx.parcel.util.saveRouteJCells
import com.xxxx.parcel.viewmodel.ParcelViewModel

/**
 * 取件路线页。
 *
 * 场地模型**完全来自用户 Excel 的填充色**（可走格 3362 个），场地结构在页面里现场自检：
 * 3 条纵向干线（西侧 F~J / 主通道 AI~AN / 东侧 CK~CP）＋ 9 条横向走廊带 ＋ 3 处闸机带。
 *
 * 顺序由 `planPickupRoute` 求**精确最优**（Held–Karp；已与暴力枚举逐例比对），
 * 并遵守用户的两条顺丰规则：
 * 1. 取了 S 件必须先在`顺丰专用闸机`**出库**（有普通件时出库后继续取，最后从`7个普通闸机`出库并出站）
 * 2. **出库 ≠ 出站**：只有顺丰件时，出库后还要走到`顺丰和无快递出口`**出站**
 *
 * 2026-10-01 新增：**逐段「怎么走」提示**（由每段的 BFS 格序列压缩而来）＋ **1/3 屏矢量图示窗格**
 * （全览/特写）。呈现位置与详略由「提示与地图（试用）」里的开关决定，用户实机比较后再定稿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    context: Context,
    viewModel: ParcelViewModel,
    navController: NavController,
) {
    val successData by viewModel.successSmsData.collectAsState()

    var jCellsText by remember { mutableStateOf(getRouteJCells(context).toString()) }
    val options = remember(jCellsText) {
        RouteOptions(jCellsPerColumn = jCellsText.toIntOrNull()?.coerceIn(1, 200) ?: 21)
    }
    val pending = remember(successData) {
        // 用「有效货格号」：解析出的货格号为空时，退回「取件码本身就是货格号」
        successData.filter {
            !it.isCompleted && effectiveCompartmentNumber(it.compartmentNumber, it.code).isNotBlank()
        }
    }
    val route = remember(pending, options) {
        planPickupRoute(
            rawCodes = pending.map { effectiveCompartmentNumber(it.compartmentNumber, it.code) },
            options = options,
        )
    }
    val byCode = remember(pending) {
        pending.associateBy {
            parseCompartmentCode(effectiveCompartmentNumber(it.compartmentNumber, it.code))
        }
    }
    val zoneCounts = remember(route) { route.zoneCounts() }

    // 提示与地图的呈现开关（实机比较用；关掉即回到 0.1.9 的样子）
    var textPlacement by remember { mutableStateOf(getGuideTextPlacement(context)) }
    var mapPlacement by remember { mutableStateOf(getGuideMapPlacement(context)) }
    var detail by remember { mutableStateOf(getGuideDetail(context)) }
    var mapView by remember { mutableStateOf(getGuideMapView(context)) }
    var mapPageEnabled by remember { mutableStateOf(isMapPageEnabled(context)) }
    var currentStop by remember(route) { mutableIntStateOf(0) }
    var fullScreenMap by remember { mutableStateOf(false) }
    var mapCollapsed by remember { mutableStateOf(false) }

    // 地图窗格留足高度（用户 2026-10-01：紫色提示占太多、地图太小 ⇒ 地图占大头，提示最多 2 行）
    val paneHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    val overlayShown = mapPlacement == GuideMapPlacement.ROUTE_OVERLAY && route.stops.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("取件路线") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                GuideSettingsCard(
                    textPlacement = textPlacement,
                    onTextPlacement = { textPlacement = it; saveGuideTextPlacement(context, it) },
                    mapPlacement = mapPlacement,
                    onMapPlacement = { mapPlacement = it; saveGuideMapPlacement(context, it) },
                    detail = detail,
                    onDetail = { detail = it; saveGuideDetail(context, it) },
                    mapView = mapView,
                    onMapView = { mapView = it; saveGuideMapView(context, it) },
                    mapPageEnabled = mapPageEnabled,
                    onMapPageEnabled = { mapPageEnabled = it; saveMapPageEnabled(context, it) },
                    onOpenMapPage = { navController.navigate("map_page") },
                )

                if (pending.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("还没有可用于规划的取件码", fontWeight = FontWeight.Medium)
                            Text(
                                "需要短信里带「货格号」（形如 D5-23、J5-21、S3-2-2628、Y5-7-1）。" +
                                    "如果没有，可以在「添加自定义取件短信」里补上货格号。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "待取 ${route.resolvedCount} 件 · 全程 ${fmtTiles(route.totalTiles)} 格",
                                fontWeight = FontWeight.Medium,
                                fontSize = 18.sp,
                            )
                            Text(
                                if (route.exact) "顺序为精确最优解（Held–Karp，≤$MAX_EXACT_ITEMS 件；已与暴力枚举逐例比对）"
                                else "件数 >$MAX_EXACT_ITEMS ⇒ 启发式近似（最近邻 / 走廊扫描两种子 + 2-opt 取优）",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "路径：入口闸机进 → 逐件取" +
                                    (if (route.hasSfCheckout) " → 顺丰专用闸机出库 → 继续逐件取" else "") +
                                    " → ${route.exit.label}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (zoneCounts.isNotEmpty()) {
                                Text(
                                    zoneCounts.entries.joinToString("　") { "${it.key.label} ${it.value} 件" },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    if (mapPlacement == GuideMapPlacement.ROUTE_INLINE && route.stops.isNotEmpty()) {
                        RouteMiniMap(
                            route = route,
                            currentStop = currentStop,
                            detail = detail,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(paneHeight),
                            initialView = mapView,
                            collapsed = mapCollapsed,
                            onCollapsedChange = { mapCollapsed = it },
                            onCurrentStopChange = { currentStop = it },
                            onExpand = { fullScreenMap = true },
                            onMapTap = { fullScreenMap = true },
                        )
                    }

                    Text("建议顺序", fontWeight = FontWeight.Medium)
                    var pickIndex = 0
                    route.stops.forEachIndexed { stopIndex, stop ->
                        val leg = route.legs.getOrNull(stopIndex)
                        val isCurrent = stopIndex == currentStop

                        if (textPlacement.onRoute && leg != null) {
                            WalkHintCard(
                                leg = leg,
                                target = (stop as? RouteStop.Pickup)?.spot,
                                detail = detail,
                                highlight = isCurrent,
                                onClick = { currentStop = stopIndex },
                                onSetCurrent = { currentStop = stopIndex },
                            )
                        }

                        when (stop) {
                            is RouteStop.Pickup -> {
                                pickIndex += 1
                                val code = stop.code
                                val sms = byCode[code]
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentStop = stopIndex },
                                    colors = if (isCurrent) {
                                        CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                                    } else {
                                        CardDefaults.cardColors()
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "$pickIndex",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp,
                                            modifier = Modifier.width(32.dp),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(code.toString(), fontWeight = FontWeight.Medium, fontSize = 17.sp)
                                            Text(code.zone.label, style = MaterialTheme.typography.bodySmall)
                                            Text(stop.spot.label, style = MaterialTheme.typography.bodySmall)
                                            if (sms != null) {
                                                if (sms.address.isNotBlank()) {
                                                    Text(sms.address, style = MaterialTheme.typography.bodySmall)
                                                }
                                                if (sms.code.isNotBlank()) {
                                                    Text("取件码 ${sms.code}", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                        if (leg != null) {
                                            Text("→ ${fmtTiles(leg.tiles)} 格", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }

                            RouteStop.SfCheckout -> {
                                // 橙色强调 + **显式步骤徽标「SF」**：与 HTML 版的停靠序列一致
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentStop = stopIndex },
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFFFFF3E0),
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        StepBadge(text = "SF", color = Color(0xFFE65100))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "顺丰出库（顺丰专用闸机）",
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 17.sp,
                                                color = Color(0xFFE65100),
                                            )
                                            Text(
                                                "取了 S 件必须在这里出库；这台不能出站" +
                                                    (if (route.exit == RouteExit.SF_EXIT) "，出站走到顺丰出口" else "，出库后继续取普通件"),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            route.sfCheckoutCell?.let {
                                                Text("通道格 ${it.row},${it.col}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        if (leg != null) {
                                            Text("→ ${fmtTiles(leg.tiles)} 格", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }

                            is RouteStop.Exit -> {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentStop = stopIndex },
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFFFFEBEE),
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        StepBadge(text = "出", color = Color(0xFFC62828))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "出站：${stop.kind.label}",
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 17.sp,
                                                color = Color(0xFFC62828),
                                            )
                                            Text(
                                                if (stop.kind == RouteExit.NORMAL_GATE)
                                                    "普通闸机同时是出库口与出站口，出完直接走人"
                                                else
                                                    "顺丰出库机不能出站 ⇒ 从顺丰出口离开",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            route.exitCell?.let {
                                                Text("通道格 ${it.row},${it.col}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                        if (leg != null) {
                                            Text("→ ${fmtTiles(leg.tiles)} 格", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (route.lockerCodes.isNotEmpty()) {
                        HorizontalDivider()
                        Text("快递柜（不在人工货架路径上）", fontWeight = FontWeight.Medium)
                        Text(
                            route.lockerCodes.joinToString("、"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "纯数字取件码对应快递柜，与人工货架不是同一套寻址方式。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (route.unresolved.isNotEmpty()) {
                        HorizontalDivider()
                        Text("无法定位", fontWeight = FontWeight.Medium)
                        Text(
                            route.unresolved.joinToString("、"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "这些没能对上场地里的货架合并区。可能是货架号越界（主货架 1~12、J 柜列 1~6、" +
                                "S 区 1~3、Y 区 1~8），或短信里本就没有货格号。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HorizontalDivider()
                Text("场地模型（由你的 Excel 自动生成）", fontWeight = FontWeight.Medium)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("可走格：3362 格 —— 逐格照抄你在 Excel 里给通道填的颜色", style = MaterialTheme.typography.bodySmall)
                        Text("纵向干线 3 条：西侧（靠闸机，列 F~J）／主通道（列 AI~AN）／东侧（列 CK~CP）", style = MaterialTheme.typography.bodySmall)
                        Text("横向走廊 9 条带（含最北 J/S 区那条）；走廊 2 格宽、货架对 1 格", style = MaterialTheme.typography.bodySmall)
                        Text("闸机带：7个普通闸机（出库+出站）／顺丰专用闸机（出库，不能出站）／顺丰和无快递出口（出站）", style = MaterialTheme.typography.bodySmall)
                        Text("路线只走通道格：每段都用网格最短路回溯出来的格序列，结构上不可能穿货架", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "四类分区：主货架区 / J 柜列区 / 顺丰 S 区 / 大件 Y 区 —— 全部纳入规划。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HorizontalDivider()
                Text("场地参数", fontWeight = FontWeight.Medium)
                NumberField(
                    value = jCellsText,
                    onValueChange = { jCellsText = it },
                    label = "J 柜列每列格数",
                    supporting = "货位清单里标着「每列实际格数与北端行号待确认」，实测只到 j5-21（⇒ ≥21 格）。" +
                        "柜列纵深按此值等比铺开（格子 1 在靠通道的外端），并计入走位。",
                    onSave = { saveRouteJCells(context, it) },
                )

                Text(
                    "仍是近似、已如实标注的部分：每货架实际格数尚未实测（普通排同一货架内不同格先视作同一点）；" +
                        "Y 区在精确版 Excel 里是一整块，只能定位到东侧通道最近点；J 每列格数按上面的值铺开。",
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(24.dp))
                }

                // 地图窗格：**占位在列表下方**（不叠在内容上、不挤占；收起时只有一行胶囊）
                if (overlayShown) {
                    RouteMiniMap(
                        route = route,
                        currentStop = currentStop,
                        detail = detail,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                            .height(if (mapCollapsed) 52.dp else paneHeight),
                        initialView = mapView,
                        collapsed = mapCollapsed,
                        onCollapsedChange = { mapCollapsed = it },
                        onCurrentStopChange = { currentStop = it },
                        onExpand = { fullScreenMap = true },
                        onMapTap = { fullScreenMap = true },
                    )
                }
            }
        }

        if (fullScreenMap && route.stops.isNotEmpty()) {
            Dialog(
                onDismissRequest = { fullScreenMap = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RouteMiniMap(
                        route = route,
                        currentStop = currentStop,
                        detail = detail,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        initialView = mapView,
                        onCurrentStopChange = { currentStop = it },
                        onExpand = { fullScreenMap = false },
                        expandLabel = "收起",
                        showStopCodes = true,
                    )
                }
            }
        }
    }
}

/** 步骤徽标（SF / 出）——与 HTML 版停靠序列的圆圈编号同义。 */
@Composable
private fun StepBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            .size(30.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/**
 * 「怎么走」提示卡：由 [VenueGuide] 把该段的 BFS 格序列压成「向东 5 格 → 向北 6 格」。
 * 点一下即把这一段设为「当前站」（图示窗格随之特写）。
 */
@Composable
private fun WalkHintCard(
    leg: RouteLeg,
    target: PickupSpot?,
    detail: GuideDetail,
    highlight: Boolean,
    onClick: () -> Unit,
    onSetCurrent: () -> Unit,
) {
    val hints = remember(leg, target, detail) { VenueGuide.describe(leg, target) }
    val shown = hints.filter {
        it.kind == VenueGuide.HintKind.MOVE ||
            it.kind == VenueGuide.HintKind.STUB_OUT ||
            it.kind == VenueGuide.HintKind.STUB_IN ||
            (detail == GuideDetail.FULL && it.kind == VenueGuide.HintKind.NOTE)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = if (highlight) {
            CardDefaults.cardColors(containerColor = Color(0xFFE8F0FE))
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "怎么走：${leg.from} → ${leg.to}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${fmtTiles(leg.tiles)} 格",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (shown.isEmpty()) {
                Text(
                    "这一段没能给出逐步指引（格序列为空）—— 直接按货格号找即可。",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                shown.forEachIndexed { i, hint ->
                    Text(
                        "${i + 1}. ${if (detail == GuideDetail.FULL) hint.text else hint.brief}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (highlight) Color(0xFF1E6FE0) else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.clickable { onSetCurrent() },
                ) {
                    Text(
                        if (highlight) "当前站" else "设为当前",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (highlight) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 「提示与地图（试用）」：用户实机比较各种呈现方式后再定稿。 */
@Composable
private fun GuideSettingsCard(
    textPlacement: GuideTextPlacement,
    onTextPlacement: (GuideTextPlacement) -> Unit,
    mapPlacement: GuideMapPlacement,
    onMapPlacement: (GuideMapPlacement) -> Unit,
    detail: GuideDetail,
    onDetail: (GuideDetail) -> Unit,
    mapView: GuideMapView,
    onMapView: (GuideMapView) -> Unit,
    mapPageEnabled: Boolean,
    onMapPageEnabled: (Boolean) -> Unit,
    onOpenMapPage: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E6FE0)),
                )
                Text(
                    "提示与地图（试用）",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                "逐段「怎么走」＋ 路线图示。位置和详略先用开关暴露，你在驿站实际看过之后再定最终形态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipRow("文字提示", GuideTextPlacement.entries.map { it.label }, textPlacement.label) { label ->
                GuideTextPlacement.entries.firstOrNull { it.label == label }?.let(onTextPlacement)
            }
            ChipRow("地图窗格", GuideMapPlacement.entries.map { it.label }, mapPlacement.label) { label ->
                GuideMapPlacement.entries.firstOrNull { it.label == label }?.let(onMapPlacement)
            }
            ChipRow("详情程度", GuideDetail.entries.map { it.label }, detail.label) { label ->
                GuideDetail.entries.firstOrNull { it.label == label }?.let(onDetail)
            }
            ChipRow("地图默认视图", GuideMapView.entries.map { it.label }, mapView.label) { label ->
                GuideMapView.entries.firstOrNull { it.label == label }?.let(onMapView)
            }
            ChipRow("独立地图页", listOf("关", "开"), if (mapPageEnabled) "开" else "关") { label ->
                onMapPageEnabled(label == "开")
            }
            if (mapPageEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = onOpenMapPage) { Text("打开地图取件页") }
                }
                Text(
                    "地图页：顶部＝当前要取的取件码，中间＝地图（货架旁写着取件码），底部＝条码；" +
                        "开着时首页右上角菜单里也会出现入口。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                "改完立刻生效；全部关掉就回到 0.1.9 的样子。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

/** 瓷砖数显示：整数不带小数点，半格显示一位。 */
private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String,
    onSave: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { ch -> ch.isDigit() }.take(3)) },
        label = { Text(label) },
        supportingText = { Text(supporting) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = { value.toIntOrNull()?.let(onSave) }) { Text("保存此项") }
    }
}
