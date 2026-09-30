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
import androidx.compose.material3.Surface
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
import com.xxxx.parcel.util.PickupZone
import com.xxxx.parcel.util.RouteExit
import com.xxxx.parcel.util.RouteOptions
import com.xxxx.parcel.util.RouteStop
import com.xxxx.parcel.util.VenueGuide
import com.xxxx.parcel.util.CheckoutOrigin
import com.xxxx.parcel.util.CompletedMarker
import com.xxxx.parcel.util.assignStableNumbers
import com.xxxx.parcel.util.classifyPickupCategory
import com.xxxx.parcel.util.compactNumbers
import com.xxxx.parcel.util.completedMarkersOf
import com.xxxx.parcel.util.containsSfCheckout
import com.xxxx.parcel.util.countSfCheckouts
import com.xxxx.parcel.util.clearSfCheckoutDone
import com.xxxx.parcel.util.effectiveCompartmentNumber
import com.xxxx.parcel.util.formatPickupCode
import com.xxxx.parcel.util.getAddressMappings
import com.xxxx.parcel.util.getCodeNotes
import com.xxxx.parcel.util.getGuideTextPlacement
import com.xxxx.parcel.util.getRouteOptions
import com.xxxx.parcel.util.isSfCheckoutDone
import com.xxxx.parcel.util.lastCheckoutOrigin
import com.xxxx.parcel.util.loadStableNumbers
import com.xxxx.parcel.util.markSfCheckoutDone
import com.xxxx.parcel.util.parseCompartmentCode
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.recentCheckoutEntries
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
    /** 「顺丰出库」卡片右侧显示还需要出库的顺丰件数（测试功能，默认关闭） */
    showSfCheckoutCount: Boolean = false,
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
    val recentCheckouts = remember(filteredParcelsData) { recentCheckoutEntries(context) }
    val sfTakenThisTrip = remember(recentCheckouts) { containsSfCheckout(recentCheckouts) }
    // 「顺丰出库」卡片右侧的件数提醒：窗口内取过、待出库的顺丰件数
    val sfPendingCount = remember(recentCheckouts) { countSfCheckouts(recentCheckouts) }
    // 顺丰是否**已经出库**（用户 2026-10-01：点卡片即代表已出库）；tick 用于点击后立刻重算
    var sfDoneTick by remember { mutableIntStateOf(0) }
    val sfDone = remember(filteredParcelsData, sfDoneTick) { isSfCheckoutDone(context) }
    val toggleSfDone: () -> Unit = {
        if (sfDone) clearSfCheckoutDone(context) else markSfCheckoutDone(context)
        sfDoneTick += 1
    }
    // 全部取完时路线为空（homeRoute == null），出站口去哪一台要按**本次行程取过什么**推断：
    // 只取过顺丰件 ⇒ 顺丰侧出站口；否则走 7 个普通闸机（出库 + 出站）。
    val exitLabelFallback = if (
        recentCheckouts.isNotEmpty() &&
        recentCheckouts.all { parseCompartmentCode(it.first)?.zone == PickupZone.SF }
    ) {
        RouteExit.SF_EXIT.label
    } else {
        RouteExit.NORMAL_GATE.label
    }
    val stationRoute = remember(filteredParcelsData, routeOptions, checkoutOrigin, sfDone) {
        planStationRoute(
            filteredParcelsData.filter { it.categoryOf() == PickupCategory.STATION },
            routeOptions,
            checkoutOrigin,
            sfCheckedOut = sfDone,
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
    // 「顺丰出库」卡的**锚点**：显示顺序里**最后一个含 S 区取件码的分组**（不管它有没有被取走）。
    // 🔴 用户 2026-10-01 两次反馈「顺丰出库卡跳到列表最上面」：原来锚点取自**当前路线里的最后一个
    //    S 件** —— S 件一被标记已取，路线里就没有 S 了 ⇒ 锚点消失 ⇒ 卡片掉到最上面（入口卡下面）。
    //    改成按**显示出来的卡片**算（已取的 S 卡仍在列表里）⇒ 卡片位置稳定，点「已出库」也不动。
    fun isSfGroup(parcel: ParcelData): Boolean = parcel.smsDataList.any {
        parseCompartmentCode(effectiveCompartmentNumber(it.compartmentNumber, it.code))?.zone == PickupZone.SF
    }
    // 「出站」步骤的提示（与顺丰出库同一个小窗口口径；文字提示开关关掉时不给文字）
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
            // 路线步骤（入口 / 顺丰出库 / 出站）在「快递站」页有件时**一直**显示。
            // 🔴 用户 2026-10-01：全部取完后**出站选项消失了** —— 因为判定用的是「当前路线还有没有停靠点」，
            //    全部取完时路线为空 ⇒ 步骤跟着一起消失。但人还在站里，出站这一步必须一直在。
            val routeStepsOn = page == 0 && routeSortEnabled && stationParcels.isNotEmpty()
            if (routeStepsOn) {
                // 顶部固定一个「入口进站」卡片（点击出示取件码）。
                // 🔴 用户 2026-10-01 两条纠正：① 「从当前位置继续」那种**已删除**（判定经常出错）；
                //    ② 入口这里**不再显示「接下来怎么走」**（和下面的卡片重复，而且按「从入口出发」算的
                //    提示对已经在站内的人不成立）。
                entries.add(ParcelListItem.Step(StepKind.ENTRANCE, hint = null))
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
            // 顺丰出库：与 HTML 版的停靠序列一致，把它当成**显式一步**插在「最后一个含 S 件分组」之后。
            // 🔴 只要**本次行程取过顺丰件**就必须一直显示（实测：把 S 件标记为已取后路线里就没有 S 件了，
            //    这一步会凭空消失）。用户点了它 = 已出库 ⇒ 路线不再绕出库机，但卡片保留（可撤销）。
            // 🔴 锚点按**显示出来的卡片**算（含已经取走的 S 卡），并且**点「已出库」不会改变它** ——
            //    否则卡片会跳到列表最上面（用户 2026-10-01 两次反馈）。
            val sfAnchorAddress = if (page == 0) {
                pageParcels.lastOrNull { isSfGroup(it) }?.address
            } else {
                null
            }
            fun sfStepItem() = ParcelListItem.Step(
                kind = StepKind.SF_CHECKOUT,
                hint = sfCheckoutHint,
                count = if (showSfCheckoutCount && !sfDone) sfPendingCount else null,
                done = sfDone,
            )
            val sfStepVisible = sfAnchorAddress != null || sfTakenThisTrip || sfDone
            if (page == 0 && routeSortEnabled && sfStepVisible) {
                val anchor = entries.indexOfLast {
                    (it as? ParcelListItem.Card)?.entry?.parcel?.address == sfAnchorAddress
                }
                // 锚点找不到（列表里已经没有 S 卡，例如隐藏了已取件）⇒ 放到普通件之后、出站之前
                if (anchor >= 0) entries.add(anchor + 1, sfStepItem()) else entries.add(sfStepItem())
            }
            // 出站：路线永远终于出站口（出库 ≠ 出站）；**全部取完后也必须在**
            if (routeStepsOn) {
                entries.add(
                    ParcelListItem.Step(
                        StepKind.EXIT,
                        exitHint,
                        homeRoute?.exit?.label ?: exitLabelFallback,
                    )
                )
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
                    // 🔴 用户 2026-10-01：「取件码距离上下胶囊距离不同，有些偏上」——
                    //    原来卡片**自带尾部 8dp** 间距 ⇒ 码上边只有 8dp、下边 8+8=16dp，看着偏上。
                    //    改成统一的「列表项之间 8dp」⇒ 每张卡内部上下对称。
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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

                            is ParcelListItem.Step -> Column(modifier = Modifier.fillMaxWidth()) {
                                // 「怎么走」放在卡的**上方**，与取件卡那一行提示同一套样式
                                item.hint?.let { StepHintChip(it) }
                                RouteStepCard(item, onShowBarcode, toggleSfDone)
                            }
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
        /** 一行「怎么走」（文字提示开关打开时才有；**渲染在卡片上方**，不塞进卡里） */
        val hint: String? = null,
        /** 附加标签：出站用路线给出的闸机名 */
        val label: String? = null,
        /** 顺丰出库：右侧提醒**还有几件要出库**；已出库时为 null（不再提醒） */
        val count: Int? = null,
        /** 顺丰出库：**已出库**（用户点过卡片）⇒ 卡片显示「已出库 · 点击撤销」 */
        val done: Boolean = false,
    ) : ParcelListItem {
        override val key: String get() = "step:${kind.name}"
    }
}

/** 路线步骤的种类（决定徽标、配色与文案）。 */
private enum class StepKind {
    /** 入口进站（刷码进入）——点击出示取件码 */
    ENTRANCE,

    /** 顺丰出库（专用闸机，不能出站） */
    SF_CHECKOUT,

    /** 出站（路线的终点）——点击出示取件码 */
    EXIT,
}

/**
 * 路线步骤卡片（入口 / 顺丰出库 / 出站）。
 *
 * 三种步骤**共用同一个容器样式**：圆角与地址卡/地图卡一致（[Corners.cardShape] = 16dp）、
 * 同一徽标尺寸、同一间距。行数按用户 2026-10-01 的要求分别精简：
 *
 * | 步骤 | 版式 |
 * |---|---|
 * | 入口进站 | **1 行**：`[入] 入口进站 … 点击出示取件码` |
 * | 顺丰出库 | 2 行（标题 + 说明），右侧可挂「N 件待出库」小签（测试开关） |
 * | 出站 | **2 行**：标题（含闸机名）+「点击出示取件码」 |
 *
 * 历史纠正：曾做成 50% 圆角胶囊（被指「圆角非常丑」）、曾把「怎么走」塞在卡内（已挪到卡上方）、
 * 「从当前位置继续」那一种**已删除**（判定经常出错）。
 */
@Composable
private fun RouteStepCard(
    step: ParcelListItem.Step,
    onShowBarcode: () -> Unit,
    onToggleSfDone: () -> Unit,
) {
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
    // 入口/出站：点击出示条码；顺丰出库：点击 = 标记已出库（再点撤销）
    val action: () -> Unit = if (step.kind == StepKind.SF_CHECKOUT) onToggleSfDone else onShowBarcode

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 列表项之间的间距由 LazyColumn 的 spacedBy(8dp) 统一给，这里不再自带
            .padding(vertical = 0.dp)
            .clickable(onClick = action),
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
            when (step.kind) {
                // 1 行版式（用户 2026-10-01：入口完全可以排成 1 行）
                StepKind.ENTRANCE -> {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Medium,
                        color = titleColor,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    )
                    Text(
                        text = "点击出示取件码",
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        maxLines = 1,
                    )
                }

                // 2 行版式：标题（含闸机名）+ 点击提示
                StepKind.EXIT -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    ) {
                        Text(title, fontWeight = FontWeight.Medium, color = titleColor)
                        Text(
                            text = "点击出示取件码",
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                        )
                    }
                }

                // 2 行版式：标题 + 说明；右侧可挂「N 件待出库」；**点击 = 标记已出库**（再点撤销）
                StepKind.SF_CHECKOUT -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    ) {
                        Text(title, fontWeight = FontWeight.Medium, color = titleColor)
                        Text(
                            text = if (step.done) {
                                "已经出库了；点击可撤销"
                            } else {
                                "取了顺丰件先在这里出库；这台不能出站"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = if (step.done) "已出库 ✓" else "点击表示已出库",
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                        )
                    }
                    if (step.done) {
                        Surface(
                            shape = Corners.pillShape,
                            color = Color(0xFF1B8A2E),
                            modifier = Modifier.clickable(onClick = onToggleSfDone),
                        ) {
                            Text(
                                text = "已出库 · 撤销",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    } else {
                        step.count?.takeIf { it > 0 }?.let { n ->
                            Box(
                                modifier = Modifier
                                    .clip(Corners.chipShape)
                                    .background(badgeColor)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "$n 件待出库",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 步骤卡**上方**那一行「怎么走」提示（顺丰出库 / 出站用）。
 *
 * 样式与取件卡上方那行提示（`AddressCard` 的 `guideHint`）保持一致：浅蓝底、圆角小块、`→` 开头。
 */
@Composable
private fun StepHintChip(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 0.dp, bottom = 4.dp),
        shape = Corners.chipShape,
        color = Color(0xFFE8F0FE),
    ) {
        Text(
            text = "→ $text",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF10366B),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
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
    sfCheckedOut: Boolean = false,
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
        sfCheckedOut = sfCheckedOut,
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
