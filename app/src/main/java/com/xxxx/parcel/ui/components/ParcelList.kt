package com.xxxx.parcel.ui.components

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.xxxx.parcel.ui.theme.Corners
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
import com.xxxx.parcel.util.hasRecentSfCheckout
import com.xxxx.parcel.util.lastCheckoutOrigin
import com.xxxx.parcel.util.loadStableNumbers
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.refreshStableNumbers
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
    /** 下拉刷新信号：每次下拉 +1（首页重读短信后传进来）⇒ 重算路线并把未取件按新顺序重排件号 */
    refreshSignal: Int = 0,
    /** 点「入口」/「出站」胶囊：全屏出示快递中心条码（没设置过条码时由上层引导去设置） */
    onShowBarcode: () -> Unit = {},
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
    // 「本次行程取过顺丰件」：读时间窗内的已取件记录（**不能只看当前路线** —— 取完 S 之后路线里就没有 S 了）。
    // 键用 filteredParcelsData：每次标记/取消已取件它都会变 ⇒ 记录变了就能立刻重算。
    val sfTakenThisTrip = remember(filteredParcelsData) { hasRecentSfCheckout(context) }
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
    // 🔴 **下拉刷新**（`refreshSignal` 每次下拉 +1）：这一次要「按新的最优顺序重排未取件的号」，
    //    而不是平时的「老件保原号 ⇒ 顺序永远不变」（用户 2026-10-01：下拉刷新就是把寻路更新一遍）。
    //    已取件的条目仍留在原位、保原号。靠 SideEffect 记录「上次处理过的信号」来判定**本次**是否刷新。
    var seenRefresh by remember { mutableIntStateOf(refreshSignal) }
    val justRefreshed = refreshSignal != seenRefresh
    SideEffect { seenRefresh = refreshSignal }
    val routeNumbers: Map<String, Int> = remember(
        stationParcels, stationRoute.orderedAddresses, routeSortEnabled, showCompleted, refreshSignal,
    ) {
        if (!routeSortEnabled) {
            emptyMap()
        } else {
            val shown = stationParcels.map { it.address }
            val numbers = when {
                // 隐藏已取件：取完就消失，号从 1 紧凑重排（原行为）
                !showCompleted -> compactNumbers(stationRoute.orderedAddresses, stickyNumbers)
                // 下拉刷新：未取件按新顺序重排（不在 pending 里的＝已取件，原地保号）
                justRefreshed -> refreshStableNumbers(
                    shown,
                    stationRoute.orderedAddresses,
                    stickyNumbers,
                )
                // 平时：稳定编号（老件保原号，新件拿最小空号）
                else -> assignStableNumbers(shown, stationRoute.orderedAddresses, stickyNumbers)
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
    // 「入口 / 出站」步骤胶囊的提示（与顺丰出库同一个小窗口口径；文字提示开关关掉时不给文字）
    val startHint: String? = remember(homeRoute, guideText) {
        val r = homeRoute ?: return@remember null
        if (!guideText.onHome) return@remember null
        r.legs.firstOrNull()?.let { VenueGuide.summarize(it) }?.takeIf { it.isNotEmpty() }
    }
    val exitHint: String? = remember(homeRoute, guideText) {
        val r = homeRoute ?: return@remember null
        if (!guideText.onHome) return@remember null
        r.legs.lastOrNull()?.let { VenueGuide.summarize(it) }?.takeIf { it.isNotEmpty() }
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
            val entries = ArrayList<ParcelListItem>(pageParcels.size + 3)
            // 路线步骤胶囊（入口 / 当前位置 / 顺丰出库 / 出站）：只在「按取件路线排序」开着时出现，
            // 与 ①②③ 序号、逐卡「怎么走」同一前提（用户 2026-10-01：首页最顶部要一个入口胶囊、
            // 出站时也要一个出站胶囊；点这两个胶囊 = 全屏出示条码，因为闸机前只有这两处要用条码）。
            val routeStepsOn = page == 0 && routeSortEnabled && homeRoute?.stops?.isNotEmpty() == true
            if (routeStepsOn) {
                // 顶部固定一个「入口进站」卡片（点一下 = 全屏出示条码）。
                // 🔴 「从当前位置继续」那个胶囊已按用户要求**删除**（2026-10-01）：
                //    它的判定是「最近一次取件记录」，实操中经常是错的 —— 与其显示错误信息不如不显示。
                //    同时：取到一半时**不再给「怎么走」提示**（那条提示按「从入口出发」算，对已经在站内的人不成立）。
                entries.add(
                    ParcelListItem.Step(
                        StepKind.ENTRANCE,
                        hint = if (checkoutOrigin == null) startHint else null,
                    )
                )
            }
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
            // 顺丰出库：与 HTML 版的停靠序列一致，把它当成**显式一步**插在最后一个 S 件之后。
            // 🔴 只要**本次行程取过顺丰件**就必须一直显示（用户 2026-10-01 实测：把 S 件的取件码标记为已取后，
            //    路线里就没有 S 件了 ⇒ hasSfCheckout 变 false ⇒ 这一步凭空消失，可人还没去闸机出库）。
            //    所以条件是「路线里还有 S 件」**或**「时间窗内取过 S 区的件」。
            val sfStepVisible = homeRoute?.hasSfCheckout == true || sfTakenThisTrip
            if (page == 0 && routeSortEnabled && sfStepVisible) {
                val anchor = entries.indexOfLast {
                    (it as? ParcelListItem.Card)?.entry?.parcel?.address == sfAfterAddress
                }
                if (anchor >= 0) {
                    entries.add(anchor + 1, ParcelListItem.Step(StepKind.SF_CHECKOUT, sfCheckoutHint))
                } else {
                    // S 件已经被取完（或者列表隐藏了已取件）⇒ 找不到锚点，放到普通件之后、出站之前
                    entries.add(ParcelListItem.Step(StepKind.SF_CHECKOUT, sfCheckoutHint))
                }
            }
            // 出站：路线永远终于出站口（出库 ≠ 出站），所以它是列表最后一步
            if (routeStepsOn) {
                entries.add(ParcelListItem.Step(StepKind.EXIT, exitHint, homeRoute?.exit?.label))
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

                            is ParcelListItem.Step -> RouteStepCard(item, onShowBarcode)
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

/** 列表项：地址卡片，或**路线的一个显式步骤**（入口 / 当前位置 / 顺丰出库 / 出站）。 */
private sealed interface ParcelListItem {
    val key: String

    data class Card(val entry: ParcelListEntry) : ParcelListItem {
        override val key: String get() = entry.key
    }

    data class Step(
        val kind: StepKind,
        /** 一行「怎么走」（文字提示开关打开时才有） */
        val hint: String? = null,
        /** 附加标签：出站用路线给出的闸机名；「从当前位置继续」用它写清从哪儿开始 */
        val label: String? = null,
    ) : ParcelListItem {
        override val key: String get() = "step:${kind.name}"
    }
}

/** 路线步骤的种类（决定徽标、配色与文案）。 */
private enum class StepKind {
    /** 入口进站（刷码进入）——点一下 = 全屏出示条码 */
    ENTRANCE,

    /** 顺丰出库（专用闸机，不能出站） */
    SF_CHECKOUT,

    /** 出站（路线的终点）——点一下 = 全屏出示条码 */
    EXIT,
}

/**
 * 路线步骤卡片（入口 / 顺丰出库 / 出站）。
 *
 * 三种步骤**共用同一个样式**：圆角与地址卡/地图卡一致（[Corners.cardShape] = 16dp）、
 * 同一徽标尺寸、同一间距。
 *
 * 🔴 用户 2026-10-01 两条纠正：
 * 1. 原来做成**胶囊**（50% 圆角）被指「圆角非常丑，让它保持和其他窗口的圆角一样」⇒ 改用 16dp；
 * 2. 「从当前位置继续」那一种**已删除**（判定经常出错）。
 *
 * [StepKind.ENTRANCE] 与 [StepKind.EXIT] 可点：全屏出示快递中心条码（只有进出闸机这两处要用它）。
 */
@Composable
private fun RouteStepCard(step: ParcelListItem.Step, onShowBarcode: () -> Unit) {
    val badge: String
    val badgeColor: Color
    val container: Color
    val titleColor: Color
    when (step.kind) {
        StepKind.ENTRANCE -> {
            badge = "入"; badgeColor = Color(0xFF1B8A2E); container = Color(0xFFE7F6E9); titleColor = Color(0xFF14601F)
        }
        StepKind.SF_CHECKOUT -> {
            badge = "SF"; badgeColor = Color(0xFFE65100); container = Color(0xFFFFF3E0); titleColor = Color(0xFFE65100)
        }
        StepKind.EXIT -> {
            badge = "出"; badgeColor = Color(0xFF5B6472); container = Color(0xFFEEF0F4); titleColor = Color(0xFF39414D)
        }
    }
    val title = when (step.kind) {
        StepKind.ENTRANCE -> "入口进站"
        StepKind.SF_CHECKOUT -> "顺丰出库（顺丰专用闸机）"
        StepKind.EXIT -> "出站" + (step.label?.let { "：$it" } ?: "")
    }
    val subtitle = when (step.kind) {
        StepKind.ENTRANCE -> "刷码进入 · 取完件从这里开始走"
        StepKind.SF_CHECKOUT -> "取了顺丰件必须先在这里出库；这台不能出站"
        StepKind.EXIT -> "出库 ≠ 出站；走到这里才结束"
    }
    // 只有「入口」「出站」需要出示条码（其余两步在闸机里不刷码）
    val tappable = step.kind == StepKind.ENTRANCE || step.kind == StepKind.EXIT

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (tappable) Modifier.clickable(onClick = onShowBarcode) else Modifier),
        shape = Corners.cardShape,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (badge.length > 1) 11.sp else 14.sp,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(title, fontWeight = FontWeight.Medium, color = titleColor)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                if (step.hint != null) {
                    Text("→ ${step.hint}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1565C0))
                }
                if (tappable) {
                    Text(
                        "点一下 = 全屏出示条码",
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                    )
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
