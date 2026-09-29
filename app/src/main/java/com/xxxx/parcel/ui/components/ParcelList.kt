package com.xxxx.parcel.ui.components

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xxxx.parcel.R
import com.xxxx.parcel.model.ParcelData
import com.xxxx.parcel.model.SmsData
import com.xxxx.parcel.util.PickupCategory
import com.xxxx.parcel.util.SiteLayout
import com.xxxx.parcel.util.classifyPickupCategory
import com.xxxx.parcel.util.effectiveCompartmentNumber
import com.xxxx.parcel.util.formatPickupCode
import com.xxxx.parcel.util.getAddressMappings
import com.xxxx.parcel.util.getCodeNotes
import com.xxxx.parcel.util.getSiteLayout
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.saveCodeNote
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
    // 「按取件路线排序」：把「快递站」页里能定位的件按最优取件顺序排开（①②③…），
    // 定位不了的（无货格号、货架号越界）保持原顺序排在后面。
    // 布局参数与「取件路线」页共用同一套。
    val routeLayout = remember { getSiteLayout(context) }
    val routeOrder: Map<String, Int> = remember(filteredParcelsData, routeSortEnabled, routeLayout) {
        if (!routeSortEnabled) {
            emptyMap()
        } else {
            stationRouteOrder(
                filteredParcelsData.filter { it.categoryOf() == PickupCategory.STATION },
                routeLayout,
            )
        }
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
            // 快递站（第 0 页）：按取件路线排序时重排；其余页保持原顺序
            val pageParcels = if (page == 0 && routeOrder.isNotEmpty()) {
                categoryParcels[page].sortedBy { routeOrder[it.address] ?: Int.MAX_VALUE }
            } else {
                categoryParcels[page]
            }
            // 快递站：地址就是短信碎片，整行去掉；快递柜：保留卡片头（显示是几号柜），但不再重复「自助取件」
            val entries = pageParcels.map { parcel ->
                ParcelListEntry(
                    parcel = parcel,
                    hideHeader = page == 0,
                    showLockerTag = page != 1,
                    routeOrder = routeOrder[parcel.address],
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
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(entries, key = { it.key }) { entry ->
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
                        )
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
) {
    val key: String get() = "card:${parcel.address}"
}

/**
 * 计算「快递站」列表的取件序号表：地址 → ①②③…
 *
 * 每个地址分组取它第一个未取件的**有效货格号**（`compartmentNumber`，为空时用取件码兜底），
 * 一起交给路径引擎求最优顺序，再把最优顺序映射回地址。定位不了的地址不出现在表里。
 */
private fun stationRouteOrder(
    parcels: List<ParcelData>,
    layout: SiteLayout,
): Map<String, Int> {
    val pairs = parcels.mapNotNull { parcel ->
        val sms = parcel.smsDataList.firstOrNull { !it.isCompleted }
            ?: parcel.smsDataList.firstOrNull()
        val code = sms?.let { effectiveCompartmentNumber(it.compartmentNumber, it.code) } ?: ""
        if (code.isEmpty()) null else parcel.address to code
    }
    if (pairs.isEmpty()) return emptyMap()

    val route = planPickupRoute(pairs.map { it.second }, layout, returnToEntrance = true)
    val remaining = pairs.toMutableList()
    val order = LinkedHashMap<String, Int>()
    var seq = 0
    route.orderedCodes.forEach { code ->
        val idx = remaining.indexOfFirst { it.second == code.toString() }
        if (idx >= 0) {
            order[remaining[idx].first] = ++seq
            remaining.removeAt(idx)
        }
    }
    return order
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
