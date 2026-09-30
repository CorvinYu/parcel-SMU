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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.xxxx.parcel.ui.components.TripStopSection
import com.xxxx.parcel.ui.components.rememberTripView
import com.xxxx.parcel.ui.components.timeFilterOptions
import com.xxxx.parcel.ui.theme.Corners
import com.xxxx.parcel.util.CompletedMarker
import com.xxxx.parcel.util.DEFAULT_BOTTOM_HEIGHT_DP
import com.xxxx.parcel.util.DEFAULT_BOTTOM_HEIGHT_SENIOR_DP
import com.xxxx.parcel.util.GuideMapPlacement
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.getBarcodeBottomHeightDp
import com.xxxx.parcel.util.getBarcodePayload
import com.xxxx.parcel.util.clearSfCheckoutDone
import com.xxxx.parcel.util.countSfCheckouts
import com.xxxx.parcel.util.markSfCheckoutDone
import com.xxxx.parcel.util.recentCheckoutEntries
import com.xxxx.parcel.util.getGuideDetail
import com.xxxx.parcel.util.getGuideMapHeightDp
import com.xxxx.parcel.util.getGuideMapView
import com.xxxx.parcel.util.getRouteOptions
import com.xxxx.parcel.util.getGuideMapPlacement
import com.xxxx.parcel.util.saveGuideMapHeightDp
import com.xxxx.parcel.util.saveMapPageEnabled
import com.xxxx.parcel.util.getHorizontalLayout
import com.xxxx.parcel.util.getPreferLockerAddress
import com.xxxx.parcel.util.getShowCodeTime
import com.xxxx.parcel.util.getShowCompartment
import com.xxxx.parcel.util.getShowCompleted
import com.xxxx.parcel.util.getTimeSort
import com.xxxx.parcel.util.hasBarcodeOriginalImage
import com.xxxx.parcel.util.isRouteSortList
import com.xxxx.parcel.util.isSfCheckoutCountEnabled
import com.xxxx.parcel.util.isSfCheckoutDone
import com.xxxx.parcel.util.saveSfCheckoutCountEnabled
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    // 底部浮窗高度：用户在浮窗顶部上下拖动调节，持久化（Float 保存，避免每帧取整丢位移）
    var bottomHeightDp by remember {
        mutableFloatStateOf(
            (getBarcodeBottomHeightDp(context).takeIf { it > 0 }
                ?: if (isSeniorMode) DEFAULT_BOTTOM_HEIGHT_SENIOR_DP else DEFAULT_BOTTOM_HEIGHT_DP).toFloat()
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
    // 「顺丰出库件数提醒（测试）」：菜单里可直接开关，默认关闭（用户 2026-10-01）
    var sfCountEnabled by remember { mutableStateOf(isSfCheckoutCountEnabled(context)) }
    // 地图窗格高度（dp；用 Float 保存，避免每帧取整丢精度导致「拖了不动」）
    var mapHeightDp by remember { mutableFloatStateOf(getGuideMapHeightDp(context).toFloat()) }
    var homeFullMap by remember { mutableStateOf(false) }
    var homeMapCollapsed by remember { mutableStateOf(false) }
    val homeMapEnabled = guideMapPlacement == GuideMapPlacement.HOME_OVERLAY
    // 下拉刷新（用户 2026-10-01「在首页下滑刷新排序，就是更新这个寻路功能」）：
    // 重读短信 → 重算最优路线 → 未取件的 ①②③ 按新顺序重排（已取件的留在原位、保原号）。
    var refreshing by remember { mutableStateOf(false) }
    var refreshSignal by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val selectedTimeFilterIndex by viewModel.timeFilterIndex.collectAsState()
    val failedData by viewModel.failedMessages.collectAsState()
    val successData by viewModel.successSmsData.collectAsState()
    // 首页「全屏地图」用**与地图取件页完全一样**的整段行程视图（见下面的 Dialog）
    val mapOptions = remember { getRouteOptions(context) }

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
                sfCountEnabled = sfCountEnabled,
                onToggleSfCount = {
                    val next = !sfCountEnabled
                    saveSfCheckoutCountEnabled(context, next)
                    sfCountEnabled = next
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
        // 下拉刷新：**整块首页内容**都在手势范围内（列表 / 地图窗格 / 底部条码）
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                refreshSignal += 1
                // 重读短信（与切换时间筛选同一条链路），列表会据此重算路线与件号
                (context as MainActivity).readAndParseSms()
                scope.launch {
                    delay(700)
                    refreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // 底部浮窗高度 = 「列表没占满时剩下的空白」与「用户拖动设定的高度」取小；
            // 列表装不下（内容高度未知）时缩到最小高度让位给列表。
            val density = LocalDensity.current
            val contentHeightDp = remember(listContentHeightPx) {
                listContentHeightPx?.let { px -> with(density) { px.toDp() } }
            }
            val minBarcodeHeight = if (isSeniorMode) 120.dp else 88.dp
            // 地图窗格与下方条码之间的空隙（用户 2026-10-01：原来 8+8 太大 ⇒ 各留 4dp）
            val paneGap = 4.dp
            // 列表仍要能看见内容：地图是**浮**在列表底部之上的，这里给列表留一点余量
            val minListHeight = 110.dp
            // 手动可调的条码上限：最多占容器一半
            val maxBarcodeHeight = maxHeight * 0.5f
            val userHeight = bottomHeightDp.dp.coerceIn(minBarcodeHeight, maxBarcodeHeight)
            // 🔴 地图高度的**天花板**：给列表留够、给条码留出它**实际会占**的位置。
            //    条码没被钉住时会自动缩到最小 ⇒ 按 minBarcodeHeight 预留；钉住了才按用户设定值。
            //    （之前一律按用户设定值 ⇒ 天花板偏小，往上拖很快就顶到上限，像是不跟手。）
            val barcodeAllowance = when {
                !barcodeBottomEnabled -> 0.dp
                barcodePinned -> userHeight + paneGap
                else -> minBarcodeHeight + paneGap
            }
            val mapHeightCeiling = (maxHeight - minListHeight - barcodeAllowance)
                .coerceAtLeast(140.dp)
                .coerceAtMost(maxHeight * 0.8f)
            // 图示窗格高度：用户拖过就用用户的（并受天花板约束），否则默认 42% 容器高
            val mapDefaultHeight = maxHeight * 0.42f
            val mapPaneHeight = (if (mapHeightDp > 0f) mapHeightDp.dp else mapDefaultHeight)
                .coerceAtMost(mapHeightCeiling)
            val mapHeight = if (homeMapCollapsed) 48.dp else mapPaneHeight
            val mapActive = homeMapEnabled && homeRoute?.stops?.isNotEmpty() == true
            // 🔴 两个窗格的抖动：条码高度是按「列表剩下的空白」算的，而列表空白又被条码高度影响 ⇒ 会来回抖。
            //    对策（用户 2026-10-01）：① 把地图窗格占的高度从「可用空白」里扣掉；
            //    ② 用户一旦手动拖过条码高度，就**钉住**（不再自动伸缩）。
            val reservedByMap = if (mapActive) mapHeight else 0.dp
            val availableBlank = contentHeightDp?.let { (maxHeight - reservedByMap - it - paneGap).coerceAtLeast(0.dp) }
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
                // 🔴 **列表与地图放在同一个 Box**：地图贴着列表底部**浮在上面** ⇒
                //    地图的圆角处露出的是**列表内容（蓝色提示胶囊等）**，而不是光秃秃的背景。
                //    （用户 2026-10-01：「你应该解决问题，而不是逃避」—— 上沿改直角只是躲开了问题。）
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
                        // 地图是浮层（不参与布局）⇒ 列表底部要留出地图的高度，最后一件才滚得出来
                        listBottomPadding = if (mapActive) mapHeight + paneGap else 0.dp,
                        // 下拉刷新信号：列表据此重排未取件的①②③
                        refreshSignal = refreshSignal,
                        // 入口 / 出站卡片：**没设置条码时直接进条码设置页**（用户 2026-10-01 两次要求）。
                        // 🔴 判定只看**解码出来的条码内容**：之前把「有原图」也算成已设置，
                        //    于是清掉内容后点它仍然进全屏页（用户看到的还是「没实现」）。
                        onShowBarcode = {
                            if (getBarcodePayload(context) != null) {
                                homeBarcodeFull = true
                            } else {
                                navController.navigate("barcode")
                            }
                        },
                        // 「顺丰出库」卡片右侧的件数提醒
                        showSfCheckoutCount = sfCountEnabled,
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

                    // 路线图示：浮在列表底部（不挤占列表、圆角处露出列表内容）
                    if (mapActive) {
                        homeRoute?.let { route ->
                            RouteMiniMap(
                                route = route,
                                currentStop = homeStop,
                                detail = guideDetail,
                                // 首页地图也默认特写（跟随「地图视图」设置，可切回全览）
                                initialView = getGuideMapView(context),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    // 🔴 用户 2026-10-01：底部**不留缝** —— 留 6dp 会让列表内容从缝里露出来
                                    //（「会露出下方的窗口，不好看」）。贴住列表框底边，与下方条码卡相接。
                                    .padding(start = 10.dp, end = 10.dp)
                                    .height(mapHeight),
                                collapsed = homeMapCollapsed,
                                onCollapsedChange = { homeMapCollapsed = it },
                                onCurrentStopChange = { homeStop = it },
                                onExpand = { homeFullMap = true },
                                onMapTap = { homeFullMap = true },
                                pickupLabels = homePickupLabels,
                                completedMarkers = homeCompletedMarkers,
                                // 首页窗格**不显示**那条紫色「怎么走」提示（与上面重复）
                                showHintPill = false,
                                onResizeDelta = { dy ->
                                    // 🔴 读**当前**状态（不能读组合时捕获的旧值）；上限 = 天花板。
                                    val currentDp = if (mapHeightDp > 0f) mapHeightDp.dp else mapDefaultHeight
                                    val deltaDp = with(density) { dy.toDp() }
                                    val next = (currentDp - deltaDp).coerceIn(140.dp, mapHeightCeiling)
                                    // 🔴 这里**不再钉住条码**：钉住会让条码从「自动的最小高度」跳到用户设定值
                                    //    （可能相差上百 dp），地图上沿随之被顶走 ⇒ 手感上就是不跟手。
                                    mapHeightDp = next.value
                                    saveGuideMapHeightDp(context, next.value.roundToInt())
                                },
                            )
                        }
                    } else if (homeMapEnabled && hasPermission) {
                        // 选了「首页底部浮层」却画不出来时，**明确说原因**（不许静默消失）
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 10.dp),
                            shape = Corners.cardShape,
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
                }

                if (barcodeBottomEnabled) {
                    BarcodeBottomCard(
                        context = context,
                        isSeniorMode = isSeniorMode,
                        heightDp = animatedBottomHeight,
                        onDrag = { delta ->
                            // 向上拖（delta 为负）⇒ 变高（Float 保存，避免取整丢位移）
                            bottomHeightDp = (bottomHeightDp - delta.value)
                                .coerceIn(
                                    minBarcodeHeight.value,
                                    maxBarcodeHeight.value,
                                )
                        },
                        onDragStart = { draggingBottom = true },
                        onDragEnd = {
                            draggingBottom = false
                            saveBarcodeBottomHeightDp(context, bottomHeightDp.roundToInt())
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
        }   // ← PullToRefreshBox 收尾
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
            BarcodeFullScreenDialog(
                context = context,
                onDismiss = { homeBarcodeFull = false },
                onOpenSettings = {
                    homeBarcodeFull = false
                    navController.navigate("barcode")
                },
            )
        }

        if (homeFullMap) {
            homeRoute?.let {
                Dialog(
                    onDismissRequest = { homeFullMap = false },
                    properties = DialogProperties(
                        usePlatformDefaultWidth = false,
                        // 🔴 用户 2026-10-01：全屏地图时底部会露出主界面（下方的地图胶囊）⇒
                        //    让对话框窗口铺满整个屏幕（含系统栏区域），背景就是一块干净的 Surface。
                        decorFitsSystemWindows = false,
                    ),
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        // 每次打开都重新读「顺丰已出库」状态；卡片上点一下也要立刻重算行程
                        var fsSfTick by remember { mutableIntStateOf(0) }
                        val sfDoneTrip = remember(successData, fsSfTick) { isSfCheckoutDone(context) }
                        val tripView = rememberTripView(successData, mapOptions, sfDoneTrip)
                        var fsCurrent by remember { mutableStateOf<Int?>(null) }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                // 内容避开状态栏 / 导航栏（含挖孔区），但背景仍铺满整屏
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                // 🔴 用户 2026-10-01：底部留出余量 ⇒ 全屏地图下沿的**圆角框**能完整收尾，
                                //    不会被系统导航条压掉（「底部地图没有框框结尾」）。
                                .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 14.dp),
                        ) {
                            // 用户 2026-10-01：把「地图取件」页的**整个第一部分**搬过来 ——
                            // 当前取件码卡（点击标记已取 / 再点恢复）+ 下方横向可滑的整段序列
                            TripStopSection(
                                context = context,
                                viewModel = viewModel,
                                view = tripView,
                                current = fsCurrent,
                                onCurrentChange = { fsCurrent = it },
                                sfCheckedOut = sfDoneTrip,
                                onToggleSfDone = {
                                    if (sfDoneTrip) clearSfCheckoutDone(context) else markSfCheckoutDone(context)
                                    fsSfTick += 1
                                },
                                showSfCount = sfCountEnabled,
                                sfPendingCount = countSfCheckouts(recentCheckoutEntries(context)),
                                onShowBarcode = { homeBarcodeFull = true },
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            RouteMiniMap(
                                route = tripView.route,
                                currentStop = tripView.stopIndexOf(tripView.clampCurrent(fsCurrent)),
                                detail = guideDetail,
                                // 全屏也跟随「地图视图」设置（默认特写）
                                initialView = getGuideMapView(context),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
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

}
