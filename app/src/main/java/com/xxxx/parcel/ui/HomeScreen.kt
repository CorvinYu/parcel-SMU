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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xxxx.parcel.MainActivity
import com.xxxx.parcel.ui.components.BarcodeBottomCard
import com.xxxx.parcel.ui.components.BarcodePresentationDialog
import com.xxxx.parcel.ui.components.BarcodeStrip
import com.xxxx.parcel.ui.components.HomeTopBar
import com.xxxx.parcel.ui.components.ParcelList
import com.xxxx.parcel.ui.components.TimeFilterSheet
import com.xxxx.parcel.ui.components.timeFilterOptions
import com.xxxx.parcel.util.getHorizontalLayout
import com.xxxx.parcel.util.getPreferLockerAddress
import com.xxxx.parcel.util.getShowCodeTime
import com.xxxx.parcel.util.getShowCompartment
import com.xxxx.parcel.util.getShowCompleted
import com.xxxx.parcel.util.getTimeSort
import com.xxxx.parcel.util.isBarcodeBackgroundEnabled
import com.xxxx.parcel.util.isBarcodeBottomEnabled
import com.xxxx.parcel.util.isBarcodeBottomFillEnabled
import com.xxxx.parcel.util.isBarcodeStripEnabled
import com.xxxx.parcel.util.saveHorizontalLayout
import com.xxxx.parcel.util.saveIndex
import com.xxxx.parcel.util.savePreferLockerAddress
import com.xxxx.parcel.util.saveShowCodeTime
import com.xxxx.parcel.util.saveShowCompartment
import com.xxxx.parcel.util.saveShowCompleted
import com.xxxx.parcel.util.saveTimeSort
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
    var preferLockerAddress by remember { mutableStateOf(getPreferLockerAddress(context)) }
    var barcodeStripEnabled by remember { mutableStateOf(isBarcodeStripEnabled(context)) }
    var barcodeBottomEnabled by remember { mutableStateOf(isBarcodeBottomEnabled(context)) }
    var barcodeBottomFillEnabled by remember { mutableStateOf(isBarcodeBottomFillEnabled(context)) }
    // 由列表上报「当前页内容高度（px）」，用来算出底部条码能占多少空白
    var listContentHeightPx by remember { mutableStateOf<Int?>(null) }
    // 条码铺作背景时，文字直接压在条码上会难读 —— 给文字容器加半透明垫子
    val barcodeBackgroundOn = remember { isBarcodeBackgroundEnabled(context) }
    var showBarcodePresentation by remember { mutableStateOf(false) }

    val selectedTimeFilterIndex by viewModel.timeFilterIndex.collectAsState()
    val failedData by viewModel.failedMessages.collectAsState()
    val successData by viewModel.successSmsData.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = if (barcodeBackgroundOn) {
                    Modifier.background(
                        if (isSystemInDarkTheme()) Color.Black.copy(alpha = 0.74f)
                        else Color.White.copy(alpha = 0.90f)
                    )
                } else {
                    Modifier
                }
            ) {
            HomeTopBar(
                context = context,
                navController = navController,
                isSeniorMode = isSeniorMode,
                isTimeSort = isTimeSort,
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
            )
                if (barcodeStripEnabled) {
                    BarcodeStrip(
                        context = context,
                        isSeniorMode = isSeniorMode,
                        onPresent = { showBarcodePresentation = true },
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
            // 列表「装得下」时，把下方空白整块让给条码；装不下就缩到最小高度、给取件码让位。
            // 用容器总高度 maxHeight（固定值）而不是列表视口来算，避免「条码变高→视口变矮→条码又变矮」的来回震荡。
            val density = LocalDensity.current
            val contentHeightDp = remember(listContentHeightPx) {
                listContentHeightPx?.let { px -> with(density) { px.toDp() } }
            }
            val minBarcodeHeight = if (isSeniorMode) 120.dp else 88.dp
            // 最多占屏幕 1/4，别把页面顶得太高（用户反馈：太高不好看）
            val maxBarcodeHeight = maxHeight * 0.25f
            val targetFillHeight = (contentHeightDp?.let { maxHeight - it } ?: maxBarcodeHeight)
                .coerceAtMost(maxBarcodeHeight)
                .coerceAtLeast(minOf(minBarcodeHeight, maxBarcodeHeight))
            // 隐藏/取出取件码时列表高度会突变，条码高度用动画跟上，避免「啪」地跳一下
            val animatedFillHeight by animateDpAsState(
                targetValue = targetFillHeight,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                label = "barcodeFillHeight"
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
                        onListContentHeightPx = { listContentHeightPx = it }
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

                if (barcodeBottomFillEnabled) {
                    BarcodeBottomCard(
                        context = context,
                        isSeniorMode = isSeniorMode,
                        onPresent = { showBarcodePresentation = true },
                        onOpenSettings = { navController.navigate("barcode") },
                        fillHeightDp = animatedFillHeight.value.toInt()
                    )
                }
            }

            // 固定高度的一条浮窗（与「底部填充」是两种形态；同时开启时以填充为准）
            if (barcodeBottomEnabled && !barcodeBottomFillEnabled) {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    BarcodeBottomCard(
                        context = context,
                        isSeniorMode = isSeniorMode,
                        onPresent = { showBarcodePresentation = true },
                        onOpenSettings = { navController.navigate("barcode") }
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
    }

    if (showBarcodePresentation) {
        BarcodePresentationDialog(
            context = context,
            onDismiss = { showBarcodePresentation = false }
        )
    }

}
