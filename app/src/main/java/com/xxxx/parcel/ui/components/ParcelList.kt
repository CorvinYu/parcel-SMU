package com.xxxx.parcel.ui.components

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.xxxx.parcel.R
import com.xxxx.parcel.model.ParcelData
import com.xxxx.parcel.model.SmsData
import com.xxxx.parcel.util.PickupCategory
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.RouteOptions
import com.xxxx.parcel.util.RouteStop
import com.xxxx.parcel.util.VenueGuide
import com.xxxx.parcel.util.CheckoutOrigin
import com.xxxx.parcel.util.CompletedMarker
import com.xxxx.parcel.util.assignStableNumbers
import com.xxxx.parcel.util.classifyPickupCategory
import com.xxxx.parcel.util.compactNumbers
import com.xxxx.parcel.util.completedMarkersOf
import com.xxxx.parcel.util.effectiveCompartmentNumber
import com.xxxx.parcel.util.formatPickupCode
import com.xxxx.parcel.util.getAddressMappings
import com.xxxx.parcel.util.getCodeNotes
import com.xxxx.parcel.util.getGuideTextPlacement
import com.xxxx.parcel.util.getRouteOptions
import com.xxxx.parcel.util.lastCheckoutOrigin
import com.xxxx.parcel.util.loadStableNumbers
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.routeAnchorCode
import com.xxxx.parcel.util.saveCodeNote
import com.xxxx.parcel.util.saveStableNumbers
import com.xxxx.parcel.viewmodel.ParcelViewModel
import kotlinx.coroutines.launch

@Composable
fun HorizontalList(
    context: Context,
    viewModel: ParcelViewModel,
    navController: NavController,
    updateAllWidget: () -> Unit,
    showCompleted: Boolean,
    showCodeTime: Boolean,
    showCompartment: Boolean = true,
    parcelsData: List<ParcelData>,
    expandedStates: androidx.compose.runtime.MutableState<MutableMap<String, Boolean>>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    preferLockerAddress: Boolean,
    isSeniorMode: Boolean,
    isTimeSort: Boolean = false,
    codeNotes: Map<String, String> = emptyMap(),
    onLongPressCode: (SmsData) -> Unit = {},
) {
    val pagerState = rememberPagerState(
        initialPage = selectedTabIndex,
        pageCount = { parcelsData.size }
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        onTabSelected(pagerState.currentPage)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 16.dp,
        ) {
            parcelsData.forEachIndexed { index, data ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                    text = {
                        Text(
                            text = data.address,
                            color = if (pagerState.currentPage == index)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) { page ->
            val parcel = parcelsData[page]
            val isExpanded = expandedStates.value[parcel.address] ?: true
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (isSeniorMode) 12.dp else 16.dp),
                verticalArrangement = Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally
            )
            {
                item {

                    AddressCard(
                        context = context,
                        viewModel = viewModel,
                        navController = navController,
                        updateAllWidget = updateAllWidget,
                        showCompleted = showCompleted,
                        showCodeTime = showCodeTime,
                        showCompartment = showCompartment,
                        parcelData = parcel,
                        expandedStates = expandedStates,
                        isExpanded = isExpanded,
                        preferLockerAddress = preferLockerAddress,
                        isSeniorMode = isSeniorMode,
                        isTimeSort = isTimeSort,
                        codeNotes = codeNotes,
                        onLongPressCode = onLongPressCode,
                    )

                }
            }
        }
    }
}

@SuppressLint("MutableCollectionMutableState")
@Composable
fun ParcelList(
    context: Context,
    viewModel: ParcelViewModel,
    navController: NavController,
    updateAllWidget: () -> Unit,
    showCompleted: Boolean,
    showCodeTime: Boolean,
    showCompartment: Boolean = true,
    isHorizontalLayout: Boolean = false,
    selectedTabIndex: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    preferLockerAddress: Boolean,
    isSeniorMode: Boolean,
    isTimeSort: Boolean = false,
    /** 首页「快递站」列表是否按最优取件顺序排列（①②③…） */
    routeSortEnabled: Boolean = false,
    /** 上报「当前页列表内容高度（px；列表可滚动时为 null）」，用于底部条码自动让位 */
    onListContentHeightPx: (Int?) -> Unit = {},
    /** 上报规划出来的路线 + 件号标签 + 已取灰点（供首页图示窗格复用，**不重复规划**） */
    onRouteComputed: (HomeRouteInfo) -> Unit = {},
    /** 列表底部留白（首页开启图示浮层时给窗格让位） */
    listBottomPadding: Dp = 0.dp,
) {
    val parcelsData by viewModel.parcelsData.collectAsState()
    val failedMessages by viewModel.failedMessages.collectAsState()
    val filteredParcelsData = if (showCompleted) parcelsData else parcelsData.filter { parcel ->
        parcel.smsDataList.any { !it.isCompleted }
    }
    val expandedStates = remember { mutableStateOf(mutableMapOf<String, Boolean>()) }
    var currentTabIndex by remember { mutableStateOf(selectedTabIndex) }
    val timeFilterIndex by viewModel.timeFilterIndex.collectAsState()
    // 取件码备注：id -> 备注
    var codeNotes by remember { mutableStateOf(getCodeNotes(context)) }
    var noteTarget by remember { mutableStateOf<SmsData?>(null) }

    LaunchedEffect(timeFilterIndex) {
        currentTabIndex = 0
    }

    noteTarget?.let { target ->
        // 优先显示归类标签，无映射回退原始地址
        val mappings = remember { getAddressMappings(context) }
        NoteDialog(
            context = context,
            code = formatPickupCode(target.code),
            address = mappings[target.address] ?: target.address,
            lockerNumber = target.lockerNumber,
            compartmentNumber = target.compartmentNumber,
            preferLockerAddress = preferLockerAddress,
            currentNote = codeNotes[target.id] ?: "",
            onDismiss = { noteTarget = null },
            onConfirm = { note ->
                saveCodeNote(context, target.id, note)
                codeNotes = getCodeNotes(context)
                noteTarget = null
            }
        )
    }

    // 时间排序模式：有未取件的地址在前，同组内按最新到件时间倒序
    val orderedParcelsData = if (isTimeSort) {
        filteredParcelsData.sortedWith(
            compareByDescending<ParcelData> { it.num > 0 }
                .thenByDescending { it.smsDataList.maxOfOrNull { s -> s.sms.timestamp } ?: 0L }
        )
    } else filteredParcelsData

    // 三大类：快递站 / 快递柜 / 校外（用户 2026-10-01 指定）。
    // 用 HorizontalPager 做「手指跟手翻页 + 点标签动画滚动」，与「横向地址」同一套效果。
    val categoryParcels = listOf(
        orderedParcelsData.filter { it.categoryOf() == PickupCategory.STATION },
        orderedParcelsData.filter { it.categoryOf() == PickupCategory.LOCKER },
        orderedParcelsData.filter { it.categoryOf() == PickupCategory.OFF_CAMPUS },
    )
    val categoryCounts = categoryParcels.map { it.size }
    val routeOptions = remember { getRouteOptions(context) }
    // 「按取件路线排序」：把「快递站」页里能定位的件按最优取件顺序排开（①②③…），
    // 定位不了的（无货格号、货架号越界）保持原顺序排在后面。
    // 🔴 设置项**每次重组都读**（不再 remember）：用户刚在「取件路线」页改过开关，
    //    返回首页必须立刻生效 —— 之前被 remember 缓存，改了像是没反应（用户 2026-10-01 反馈）。
    val guideText = getGuideTextPlacement(context)
    // 🔴 路线**总是**规划：地图窗格 / 顺丰出库步骤都要用，
    //    不能因为「按取件路线排序」关着就整条消失（那是另一件事）。
    //    ① ② ③ 序号与逐卡「怎么走」提示仍只在排序开启时展示（它们以顺序为前提）。
    // 刚取完的那一件在哪（3 小时内）：路线从**现场那一点**接着走，并在图上留一个灰点（用户 2026-10-01）
    val checkoutOrigin = remember(filteredParcelsData, routeOptions) {
        lastCheckoutOrigin(context, routeOptions)
    }
    val stationRoute = remember(filteredParcelsData, routeOptions, checkoutOrigin) {
        planStationRoute(
            filteredParcelsData.filter { it.categoryOf() == PickupCategory.STATION },
            routeOptions,
            checkoutOrigin,
        )
    }
    val homeRoute = stationRoute.route

    // ---- 稳定件号（用户 2026-10-01）----
    //   不隐藏已取件 ⇒ 取完的那条**留在原地、保留原号**（此前会掉到最末尾）；
    //   隐藏已取件   ⇒ 取完消失、编号从 1 紧凑重排（原本就是这个行为，保持）
    val stationParcels = categoryParcels[0]
    val stickyNumbers = remember { loadStableNumbers(context) }
    val routeNumbers: Map<String, Int> = remember(
        stationParcels, stationRoute.orderedAddresses, routeSortEnabled, showCompleted,
    ) {
        if (!routeSortEnabled) {
            emptyMap()
        } else {
            val numbers = if (showCompleted) {
                assignStableNumbers(
                    stationParcels.map { it.address },
                    stationRoute.orderedAddresses,
                    stickyNumbers,
                )
            } else {
                compactNumbers(stationRoute.orderedAddresses, stickyNumbers)
            }
            saveStableNumbers(context, numbers)
            numbers
        }
    }

    // 地图标记要用的「货格号 → 件号」（与卡片上的 ①②③ 同一个号；没有稳定号时退回访问顺序）
    val pickupLabels: Map<String, String> = remember(homeRoute, stationRoute, routeNumbers) {
        val out = LinkedHashMap<String, String>()
        val r = homeRoute ?: return@remember out
        var visitOrder = 0
        r.stops.forEach { stop ->
            if (stop is RouteStop.Pickup) {
                visitOrder += 1
                val address = stationRoute.addressByCode[stop.code.toString()]
                val number = address?.let { routeNumbers[it] } ?: visitOrder
                out[stop.code.toString()] = number.toString()
            }
        }
        out
    }
    LaunchedEffect(homeRoute, pickupLabels, checkoutOrigin) {
        onRouteComputed(
            HomeRouteInfo(
                route = homeRoute,
                pickupLabels = pickupLabels,
                completedMarkers = completedMarkersOf(checkoutOrigin),
            )
        )
    }

    // 文字提示（首页开关打开时）：**按地址**挂一行「怎么走」（与件号解耦）；顺丰出库那一段单独给
    val hintByAddress: Map<String, String> = remember(homeRoute, guideText, stationRoute) {
        val out = LinkedHashMap<String, String>()
        val r = homeRoute
        if (r == null || !guideText.onHome) return@remember out
        r.stops.forEachIndexed { i, stop ->
            if (stop is RouteStop.Pickup) {
                val leg = r.legs.getOrNull(i) ?: return@forEachIndexed
                val address = stationRoute.addressByCode[stop.code.toString()] ?: return@forEachIndexed
                val text = VenueGuide.summarize(leg, stop.spot)
                if (text.isNotEmpty()) out[address] = text
            }
        }
        out
    }
    val sfCheckoutHint: String? = remember(homeRoute, guideText) {
        val r = homeRoute
        if (r == null || !guideText.onHome) return@remember null
        val i = r.stops.indexOfFirst { it is RouteStop.SfCheckout }
        if (i < 0) null else r.legs.getOrNull(i)?.let { VenueGuide.summarize(it) }?.takeIf { it.isNotEmpty() }
    }
    // 顺丰出库那一步要插在「最后一个 S 件」对应的卡片之后（按地址定位，与件号无关）
    val sfAfterAddress: String? = remember(homeRoute, stationRoute) {
        val r = homeRoute ?: return@remember null
        val sfIdx = r.stops.indexOfFirst { it is RouteStop.SfCheckout }
        if (sfIdx < 0) return@remember null
        val lastPick = r.stops.take(sfIdx).filterIsInstance<RouteStop.Pickup>().lastOrNull()
        lastPick?.let { stationRoute.addressByCode[it.code.toString()] }
    }
    val defaultCategoryIndex = categoryCounts.indexOfFirst { it > 0 }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = defaultCategoryIndex,
        pageCount = { PickupCategory.entries.size },
    )
    val scope = rememberCoroutineScope()

    if (isHorizontalLayout && filteredParcelsData.isNotEmpty()) {
        HorizontalList(
            context = context,
            viewModel = viewModel,
            navController = navController,
            updateAllWidget = updateAllWidget,
            showCompleted = showCompleted,
            showCodeTime = showCodeTime,
            showCompartment = showCompartment,
            parcelsData = orderedParcelsData,
            expandedStates = expandedStates,
            selectedTabIndex = currentTabIndex,
            onTabSelected = {
                currentTabIndex = it
                onTabSelected(it)
            },
            preferLockerAddress = preferLockerAddress,
            isSeniorMode = isSeniorMode,
            isTimeSort = isTimeSort,
            codeNotes = codeNotes,
            onLongPressCode = { noteTarget = it },
        )
        return
    }

    if (filteredParcelsData.isEmpty()) {
        EmptyParcelView(
            navController = navController,
            isSeniorMode = isSeniorMode,
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            PickupCategory.entries.forEachIndexed { index, category ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Text(
                            text = if (categoryCounts[index] > 0) {
                                "${category.label} ${categoryCounts[index]}"
                            } else {
                                category.label
                            },
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) { page ->
            // 快递站（第 0 页）：按**稳定件号**排序（已取件的条目因此留在原地、序号不变）；其余页保持原顺序
            val pageParcels = if (page == 0 && routeNumbers.isNotEmpty()) {
                categoryParcels[page].sortedBy { routeNumbers[it.address] ?: Int.MAX_VALUE }
            } else {
                categoryParcels[page]
            }
            // 快递站：地址就是短信碎片，整行去掉；快递柜：保留卡片头（显示是几号柜），但不再重复「自助取件」
            val entries = ArrayList<ParcelListItem>(pageParcels.size + 1)
            pageParcels.forEach { parcel ->
                val number = routeNumbers[parcel.address]
                entries.add(
                    ParcelListItem.Card(
                        ParcelListEntry(
                            parcel = parcel,
                            hideHeader = page == 0,
                            showLockerTag = page != 1,
                            routeOrder = number,
                            hint = if (guideText.onHome && routeSortEnabled) {
                                hintByAddress[parcel.address]
                            } else {
                                null
                            },
                        )
                    )
                )
            }
            // 顺丰出库：与 HTML 版的停靠序列一致，把它当成**显式一步**插在最后一个 S 件之后
            if (page == 0 && routeSortEnabled && homeRoute?.hasSfCheckout == true && sfAfterAddress != null) {
                val anchor = entries.indexOfLast {
                    (it as? ParcelListItem.Card)?.entry?.parcel?.address == sfAfterAddress
                }
                if (anchor >= 0) {
                    entries.add(anchor + 1, ParcelListItem.SfCheckout(sfCheckoutHint))
                }
            }
            val pageListState = rememberLazyListState()
            val layoutInfo = pageListState.layoutInfo
            // 把「当前页的列表内容高度」上报首页，用于底部条码自动让位
            LaunchedEffect(layoutInfo, page, pagerState.currentPage) {
                if (page == pagerState.currentPage) {
                    onListContentHeightPx(
                        when {
                            // 空列表：下方整块都是空白，交给底部浮窗
                            layoutInfo.totalItemsCount == 0 -> 0
                            // 全部可见：上报内容高度，底部浮窗据此算「还能占多少空白」
                            layoutInfo.visibleItemsInfo.size == layoutInfo.totalItemsCount ->
                                layoutInfo.visibleItemsInfo.sumOf { it.size }
                            // 列表可滚动：没有空白可让，底部浮窗缩到最小
                            else -> null
                        }
                    )
                }
            }

            val showUnparsedHint = page == 2 && failedMessages.isNotEmpty()
            if (entries.isEmpty() && !showUnparsedHint) {
                EmptyParcelView(navController = navController, isSeniorMode = isSeniorMode)
            } else {
                LazyColumn(
                    state = pageListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (isSeniorMode) 12.dp else 16.dp),
                    contentPadding = PaddingValues(bottom = listBottomPadding),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(entries, key = { it.key }) { item ->
                        when (item) {
                            is ParcelListItem.Card -> {
                                val entry = item.entry
                                val result = entry.parcel
                                val isExpanded = expandedStates.value[result.address] ?: true
                                AddressCard(
                                    context = context,
                                    viewModel = viewModel,
                                    navController = navController,
                                    updateAllWidget = updateAllWidget,
                                    showCompleted = showCompleted,
                                    showCodeTime = showCodeTime,
                                    showCompartment = showCompartment,
                                    parcelData = result,
                                    expandedStates = expandedStates,
                                    isExpanded = isExpanded,
                                    preferLockerAddress = preferLockerAddress,
                                    isSeniorMode = isSeniorMode,
                                    isTimeSort = isTimeSort,
                                    codeNotes = codeNotes,
                                    onLongPressCode = { noteTarget = it },
                                    showLockerTag = entry.showLockerTag,
                                    hideHeader = entry.hideHeader,
                                    routeOrder = entry.routeOrder,
                                    guideHint = entry.hint,
                                )
                            }

                            is ParcelListItem.SfCheckout -> SfCheckoutListItem(hint = item.hint)
                        }
                    }
                    if (showUnparsedHint) {
                        item(key = "unparsed_hint") {
                            Text(
                                text = "另有 ${failedMessages.size} 条短信没能解析出取件码，" +
                                    "可到「解析失败」里对照原文。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 列表条目：一个地址分组卡片 */
private data class ParcelListEntry(
    val parcel: ParcelData,
    val hideHeader: Boolean,
    val showLockerTag: Boolean,
    /** 「按取件路线排序」时的取件序号（1 起）；null 表示这件不在路线里（无货格号等） */
    val routeOrder: Int? = null,
    /** 一行「怎么走」短提示（首页开关打开时才有） */
    val hint: String? = null,
) {
    val key: String get() = "card:${parcel.address}"
}

/** 列表项：地址卡片，或**顺丰出库**这个显式步骤（与 HTML 版停靠序列一致）。 */
private sealed interface ParcelListItem {
    val key: String

    data class Card(val entry: ParcelListEntry) : ParcelListItem {
        override val key: String get() = entry.key
    }

    data class SfCheckout(val hint: String?) : ParcelListItem {
        override val key: String get() = "sf_checkout"
    }
}

/** 顺丰出库步骤卡（橙色，与「取件路线」页同一套说法）。 */
@Composable
private fun SfCheckoutListItem(hint: String?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE65100)),
                contentAlignment = Alignment.Center,
            ) {
                Text("SF", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    "顺丰出库（顺丰专用闸机）",
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFE65100),
                )
                Text(
                    "取了顺丰件必须先在这里出库；这台不能出站",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (hint != null) {
                    Text("→ $hint", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1565C0))
                }
            }
        }
    }
}

/** 首页要用的规划结果：完整路线（图示窗格复用）＋ 地址↔货格号的对照。 */
private data class StationRoute(
    val route: PickupRoute?,
    /** 未取件的地址，按最优取件顺序（稳定件号的分配顺序） */
    val orderedAddresses: List<String> = emptyList(),
    /** 货格号 → 地址（把路线上的停靠点映射回列表卡片） */
    val addressByCode: Map<String, String> = emptyMap(),
)

/** 首页把规划结果交给上层（首页地图窗格用它，避免二次规划）。 */
data class HomeRouteInfo(
    val route: PickupRoute?,
    val pickupLabels: Map<String, String> = emptyMap(),
    val completedMarkers: List<CompletedMarker> = emptyList(),
)

/**
 * 计算「快递站」列表的取件顺序。
 *
 * 每个地址分组只取它**尚未取件**的**有效货格号**（`compartmentNumber`，为空时用取件码兜底），
 * 一起交给路径引擎求最优顺序。**已取完的地址不参与规划**（会从路线与地图上消失）。
 */
private fun planStationRoute(
    parcels: List<ParcelData>,
    options: RouteOptions,
    origin: CheckoutOrigin? = null,
): StationRoute {
    val pending = parcels.mapNotNull { parcel ->
        // 🔴 只把**未取件**的短信交给规划：全取完的地址返回 null
        val uncompleted = parcel.smsDataList
            .filter { !it.isCompleted }
            .map { it.compartmentNumber to it.code }
        val code = routeAnchorCode(uncompleted) ?: return@mapNotNull null
        Triple(parcel.address, code, parcel)
    }
    if (pending.isEmpty()) return StationRoute(null)

    // 起点：刚取完 ⇒ 从现场那一点接着走；否则从入口进
    val route = planPickupRoute(
        rawCodes = pending.map { it.second },
        options = options,
        startCell = origin?.cell,
        startLabel = origin?.label ?: "入口闸机",
    )
    val remaining = pending.toMutableList()
    val orderedAddresses = ArrayList<String>(remaining.size)
    val addressByCode = LinkedHashMap<String, String>()
    route.orderedCodes.forEach { code ->
        val idx = remaining.indexOfFirst { it.second == code.toString() }
        if (idx >= 0) {
            orderedAddresses += remaining[idx].first
            addressByCode[code.toString()] = remaining[idx].first
            remaining.removeAt(idx)
        }
    }
    return StationRoute(route, orderedAddresses, addressByCode)
}

/** 该地址分组属于哪一大类（同组取第一条短信的正文判定）。 */
private fun ParcelData.categoryOf(): PickupCategory {
    val first = smsDataList.firstOrNull() ?: return PickupCategory.OFF_CAMPUS
    return classifyPickupCategory(first.code, first.sms.body)
}


@Composable
private fun EmptyParcelView(
    navController: NavController,
    isSeniorMode: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 空状态图标
        Icon(
            painter = painterResource(id = R.drawable.ic_empty_package),
            contentDescription = null,
            modifier = Modifier.size(if (isSeniorMode) 120.dp else 80.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 主标题
        Text(
            text = "暂无取件码",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )



        Spacer(modifier = Modifier.height(32.dp))

        // 添加自定义短信按钮
        Button(
            onClick = {
                navController.navigate("add_custom_sms/ ")
            },
            modifier = Modifier
//                .fillMaxWidth(0.8f)
                .padding(0.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(if (isSeniorMode) 32.dp else 20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "添加自定义取件短信",
                style = if (isSeniorMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 提示文本
        Text(
            text = "您可以手动添加取件短信或取件码",
            style = if (isSeniorMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
