package com.xxxx.parcel.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.snap
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.xxxx.parcel.MainActivity
import com.xxxx.parcel.ui.components.BarcodeBottomCard
import com.xxxx.parcel.ui.components.BarcodeFullScreenDialog
import com.xxxx.parcel.ui.components.BarcodeStrip
import com.xxxx.parcel.ui.components.HomeTopBar
import com.xxxx.parcel.ui.components.HomeRouteInfo
import com.xxxx.parcel.ui.components.ParcelList
import com.xxxx.parcel.ui.components.RouteMiniMap
import com.xxxx.parcel.ui.components.TimeFilterSheet
import com.xxxx.parcel.ui.components.timeFilterOptions
import com.xxxx.parcel.util.CompletedMarker
import com.xxxx.parcel.util.DEFAULT_BOTTOM_HEIGHT_DP
import com.xxxx.parcel.util.DEFAULT_BOTTOM_HEIGHT_SENIOR_DP
import com.xxxx.parcel.util.GuideMapPlacement
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.getBarcodeBottomHeightDp
import com.xxxx.parcel.util.getGuideDetail
import com.xxxx.parcel.util.getGuideMapHeightDp
import com.xxxx.parcel.util.getGuideMapPlacement
import com.xxxx.parcel.util.saveGuideMapHeightDp
import com.xxxx.parcel.util.saveMapPageEnabled
import com.xxxx.parcel.util.getHorizontalLayout
import com.xxxx.parcel.util.getPreferLockerAddress
import com.xxxx.parcel.util.getShowCodeTime
import com.xxxx.parcel.util.getShowCompartment
import com.xxxx.parcel.util.getShowCompleted
import com.xxxx.parcel.util.getTimeSort
import com.xxxx.parcel.util.isRouteSortList
import com.xxxx.parcel.util.isBarcodeBottomPinned
import com.xxxx.parcel.util.isMapPageEnabled
import com.xxxx.parcel.util.isBarcodeBottomEnabled
import com.xxxx.parcel.util.isBarcodeStripEnabled
import com.xxxx.parcel.util.saveBarcodeBottomHeightDp
import com.xxxx.parcel.util.saveBarcodeBottomPinned
import com.xxxx.parcel.util.saveHorizontalLayout
import com.xxxx.parcel.util.saveIndex
import com.xxxx.parcel.util.savePreferLockerAddress
import com.xxxx.parcel.util.saveShowCodeTime
import com.xxxx.parcel.util.saveShowCompartment
import com.xxxx.parcel.util.saveShowCompleted
import com.xxxx.parcel.util.saveTimeSort
import com.xxxx.parcel.util.saveRouteSortList
import com.xxxx.parcel.viewmodel.ParcelViewModel

@SuppressLint("StateFlowValueCalledInComposition")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    context: Context,
    viewModel: ParcelViewModel,
    navController: NavController,
    hasPermission: Boolean,
    onCallBack: () -> Unit,
    updateAllWidget: () -> Unit,
    isSeniorMode: Boolean,
    onSeniorModeChanged: (Boolean) -> Unit,
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var showCompleted by remember { mutableStateOf(getShowCompleted(context)) }
    var showCodeTime by remember { mutableStateOf(getShowCodeTime(context)) }
    var showCompartment by remember { mutableStateOf(getShowCompartment(context)) }
    var isHorizontalLayout by remember { mutableStateOf(getHorizontalLayout(context)) }
    var isTimeSort by remember { mutableStateOf(getTimeSort(context)) }
    var isRouteSort by remember { mutableStateOf(isRouteSortList(context)) }
    var preferLockerAddress by remember { mutableStateOf(getPreferLockerAddress(context)) }
    var barcodeStripEnabled by remember { mutableStateOf(isBarcodeStripEnabled(context)) }
    var barcodeBottomEnabled by remember { mutableStateOf(isBarcodeBottomEnabled(context)) }
    // 底部浮窗高度：用户在浮窗顶部上下拖动调节，持久化
    var bottomHeightDp by remember {
        mutableStateOf(
            getBarcodeBottomHeightDp(context).takeIf { it > 0 }
                ?: if (isSeniorMode) DEFAULT_BOTTOM_HEIGHT_SENIOR_DP else DEFAULT_BOTTOM_HEIGHT_DP
        )
    }
    var draggingBottom by remember { mutableStateOf(false) }
    // 底部条码窗格是否已被用户钉住高度（钉住后不再自动伸缩，避免与地图窗格互相挤）
    var barcodePinned by remember { mutableStateOf(isBarcodeBottomPinned(context)) }
    // 条码铺作背景的功能已删除，这里不再需要给顶栏加垫子
    var listContentHeightPx by remember { mutableStateOf<Int?>(null) }

    // 路线图示窗格（首页浮层）：路线由 ParcelList 规划后上报，这里直接用，不重复规划
    val guideMapPlacement = getGuideMapPlacement(context)
    val guideDetail = getGuideDetail(context)
    var homeRoute by remember { mutableStateOf<PickupRoute?>(null) }
    var homePickupLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var homeCompletedMarkers by remember { mutableStateOf<List<CompletedMarker>>(emptyList()) }
    var homeStop by remember { mutableIntStateOf(0) }
    var homeBarcodeFull by remember { mutableStateOf(false) }
    // 「地图取件模式」：菜单里可直接开关（用户 2026-10-01）
    var mapPageEnabled by remember { mutableStateOf(isMapPageEnabled(context)) }
    // 地图窗格高度（0 = 用默认比例；用户上下拖动后持久化，用户 2026-10-01）
    var mapHeightDp by remember { mutableIntStateOf(getGuideMapHeightDp(context)) }
    var homeFullMap by remember { mutableStateOf(false) }
    var homeMapCollapsed by remember { mutableStateOf(false) }
    val homeMapEnabled = guideMapPlacement == GuideMapPlacement.HOME_OVERLAY

    val selectedTimeFilterIndex by viewModel.timeFilterIndex.collectAsState()
    val failedData by viewModel.failedMessages.collectAsState()
    val successData by viewModel.successSmsData.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
            ) {
            HomeTopBar(
                context = context,
                navController = navController,
                isSeniorMode = isSeniorMode,
                isTimeSort = isTimeSort,
                isRouteSort = isRouteSort,
                preferLockerAddress = preferLockerAddress,
                isHorizontalLayout = isHorizontalLayout,
                showCompleted = showCompleted,
                showCodeTime = showCodeTime,
                showCompartment = showCompartment,
                currentFilterLabel = timeFilterOptions[selectedTimeFilterIndex],
                successCount = successData.size,
                failedCount = failedData.size,
                onFilterClick = { showBottomSheet = true },
                onSuccessCountClick = { navController.navigate("success_sms") },
                onFailedCountClick = { navController.navigate("fail_sms") },
                onToggleTimeSort = {
                    val new = !isTimeSort
                    saveTimeSort(context, new)
                    isTimeSort = new
                },
                onToggleRouteSort = {
                    val new = !isRouteSort
                    saveRouteSortList(context, new)
                    isRouteSort = new
                },
                onTogglePreferLockerAddress = {
                    val new = !preferLockerAddress
                    savePreferLockerAddress(context, new)
                    preferLockerAddress = new
                    viewModel.setPreferLockerAddress(new)
                    (context as MainActivity).readAndParseSms()
                },
                onToggleHorizontalLayout = {
                    val new = !isHorizontalLayout
                    saveHorizontalLayout(context, new)
                    isHorizontalLayout = new
                },
                onToggleShowCompleted = {
                    val new = !showCompleted
                    saveShowCompleted(context, new)
                    showCompleted = new
                },
                onToggleShowCodeTime = {
                    val new = !showCodeTime
                    saveShowCodeTime(context, new)
                    showCodeTime = new
                },
                onToggleShowCompartment = {
                    val new = !showCompartment
                    saveShowCompartment(context, new)
                    showCompartment = new
                },
                onSeniorModeChanged = onSeniorModeChanged,
                mapPageEnabled = mapPageEnabled,
                onOpenMapPage = { navController.navigate("map_page") },
                onToggleMapPage = {
                    val next = !mapPageEnabled
                    saveMapPageEnabled(context, next)
                    mapPageEnabled = next
                },
            )
                if (barcodeStripEnabled) {
                    BarcodeStrip(
                        context = context,
                        isSeniorMode = isSeniorMode,
                        onOpenSettings = { navController.navigate("barcode") }
                    )
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 底部浮窗高度 = 「列表没占满时剩下的空白」与「用户拖动设定的高度」取小；
            // 列表装不下（内容高度未知）时缩到最小高度让位给列表。
            val density = LocalDensity.current
            val contentHeightDp = remember(listContentHeightPx) {
                listContentHeightPx?.let { px -> with(density) { px.toDp() } }
            }
            val minBarcodeHeight = if (isSeniorMode) 120.dp else 88.dp
            // 图示窗格高度：用户拖过就用用户的，否则默认 42% 容器高（用户 2026-10-01：地图要占大头）
            val mapPaneHeight = if (mapHeightDp > 0) mapHeightDp.dp else maxHeight * 0.42f
            val mapHeight = if (homeMapCollapsed) 48.dp else mapPaneHeight
            // 拖动上限在这里先算好（lambda 里不能直接用 BoxWithConstraints 的 maxHeight）
            val mapHeightMax = maxHeight * 0.75f
            val mapActive = homeMapEnabled && homeRoute?.stops?.isNotEmpty() == true
            // 手动可调的上限：最多占容器一半，别把列表挤没
            val maxBarcodeHeight = maxHeight * 0.5f
            val userHeight = bottomHeightDp.dp.coerceIn(minBarcodeHeight, maxBarcodeHeight)
            // 🔴 两个窗格的抖动：条码高度是按「列表剩下的空白」算的，而列表空白又被条码高度影响 ⇒ 会来回抖。
            //    对策（用户 2026-10-01）：① 把地图窗格占的高度从「可用空白」里扣掉；
            //    ② 用户一旦手动拖过条码高度，就**钉住**（不再自动伸缩）。
            val reservedByMap = if (mapActive) mapHeight else 0.dp
            val availableBlank = contentHeightDp?.let { (maxHeight - reservedByMap - it - 8.dp).coerceAtLeast(0.dp) }
            val targetBottomHeight = when {
                barcodePinned -> userHeight
                availableBlank == null -> minBarcodeHeight
                else -> availableBlank.coerceIn(minBarcodeHeight, userHeight)
            }
            // 拖动过程中用 snap，避免动画拖后腿
            val animatedBottomHeight by animateDpAsState(
                targetValue = if (draggingBottom) userHeight else targetBottomHeight,
                animationSpec = if (draggingBottom) snap() else tween(320, easing = FastOutSlowInEasing),
                label = "barcodeBottomHeight"
            )

            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    if (hasPermission) ParcelList(
                        context = context,
                        viewModel = viewModel,
                        navController = navController,
                        updateAllWidget = updateAllWidget,
                        showCompleted = showCompleted,
                        showCodeTime = showCodeTime,
                        showCompartment = showCompartment,
                        isHorizontalLayout = isHorizontalLayout,
                        preferLockerAddress = preferLockerAddress,
                        isSeniorMode = isSeniorMode,
                        isTimeSort = isTimeSort,
                        routeSortEnabled = isRouteSort,
                        onListContentHeightPx = { listContentHeightPx = it },
                        onRouteComputed = { info ->
                            homeRoute = info.route
                            homePickupLabels = info.pickupLabels
                            homeCompletedMarkers = info.completedMarkers
                            homeStop = 0
                        },
                        // 地图改成占位（不遮挡内容）⇒ 列表不再需要底部留白
                        listBottomPadding = 0.dp,
                    ) else
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(onClick = { onCallBack() }) {
                                Text("获取短信权限")
                            }
                        }
                }

                // 路线图示：**占位在列表下方**（不叠在卡片上、不挤占列表内容；收起时只有一行胶囊）
                if (mapActive) {
                    homeRoute?.let { route ->
                        RouteMiniMap(
                            route = route,
                            currentStop = homeStop,
                            detail = guideDetail,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
                                .height(mapHeight),
                            collapsed = homeMapCollapsed,
                            onCollapsedChange = { homeMapCollapsed = it },
                            onCurrentStopChange = { homeStop = it },
                            onExpand = { homeFullMap = true },
                            onMapTap = { homeFullMap = true },
                            pickupLabels = homePickupLabels,
                            completedMarkers = homeCompletedMarkers,
                            onResizeDelta = { dy ->
                                // 向上拖（dy<0）⇒ 变高；上限不超过容器的 3/4，下限 140dp
                                val deltaDp = with(density) { dy.toDp() }
                                val next = (mapHeight - deltaDp).coerceIn(140.dp, mapHeightMax)
                                mapHeightDp = next.value.toInt()
                                saveGuideMapHeightDp(context, mapHeightDp)
                            },
                        )
                    }
                } else if (homeMapEnabled && hasPermission) {
                    // 选了「首页底部浮层」却画不出来时，**明确说原因**（不许静默消失）
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = "路线图：暂无可规划的取件码（需要「快递站」页里有带「货格号」的未取件；" +
                                "已取完的会自动从图上消失）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }

                if (barcodeBottomEnabled) {
                    BarcodeBottomCard(
                        context = context,
                        isSeniorMode = isSeniorMode,
                        heightDp = animatedBottomHeight,
                        onDrag = { delta ->
                            // 向上拖（delta 为负）⇒ 变高
                            bottomHeightDp = (bottomHeightDp - delta.value).toInt()
                                .coerceIn(
                                    minBarcodeHeight.value.toInt(),
                                    maxBarcodeHeight.value.toInt(),
                                )
                        },
                        onDragStart = { draggingBottom = true },
                        onDragEnd = {
                            draggingBottom = false
                            saveBarcodeBottomHeightDp(context, bottomHeightDp)
                            // 用户手动定过高度 ⇒ 钉住，别再自动伸缩（否则会和地图窗格来回抖）
                            saveBarcodeBottomPinned(context, true)
                            barcodePinned = true
                        },
                        onOpenSettings = { navController.navigate("barcode") },
                        // 点一下 = 全屏出示条码（用户 2026-10-01：恢复此功能）；长按才进设置
                        onTap = { homeBarcodeFull = true },
                    )
                }
            }
        }
        if (showBottomSheet) TimeFilterSheet(
            isSeniorMode = isSeniorMode,
            onOptionSelected = { index ->
                saveIndex(context, index)
                viewModel.setTimeFilterIndex(index)
                // 重新根据过滤时间读取短信
                (context as MainActivity).readAndParseSms()
            },
            onDismiss = { showBottomSheet = false }
        )

        if (homeBarcodeFull) {
            BarcodeFullScreenDialog(context = context, onDismiss = { homeBarcodeFull = false })
        }

        if (homeFullMap) {
            homeRoute?.let { route ->
                Dialog(
                    onDismissRequest = { homeFullMap = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        RouteMiniMap(
                            route = route,
                            currentStop = homeStop,
                            detail = guideDetail,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            onCurrentStopChange = { homeStop = it },
                            onExpand = { homeFullMap = false },
                            expandLabel = "收起",
                            pickupLabels = homePickupLabels,
                            showStopCodes = true,
                            completedMarkers = homeCompletedMarkers,
                        )
                    }
                }
            }
        }
    }

}
