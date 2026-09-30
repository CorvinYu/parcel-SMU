package com.xxxx.parcel.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxxx.parcel.ui.theme.Corners
import com.xxxx.parcel.util.CompletedMarker
import com.xxxx.parcel.util.GridCell
import com.xxxx.parcel.util.GuideDetail
import com.xxxx.parcel.util.GuideMapView
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.RouteStop
import com.xxxx.parcel.util.SiteData
import com.xxxx.parcel.util.StopGroup
import com.xxxx.parcel.util.VenueGuide
import com.xxxx.parcel.util.groupRouteStops
import com.xxxx.parcel.util.siteEntranceCell
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 路线图示窗格（1/3 屏左右，**矢量绘制**，不内嵌任何图片）。
 *
 * 2026-10-01 第三版：按用户在电脑上挑定的风格落回（原型见 `docs/route-map-prototype.html`）
 * - **亮色 = 原型 A，暗色 = 原型 B**（跟随系统 `isSystemInDarkTheme()`），两套配色都适配
 * - **只画货架/柜列/区域的格子**（不再铺通道格），加虚线场地外框
 * - **相机动画**：`cx/cy/scale` 三个 `Animatable` 用 `CubicBezierEasing` 串联飞行；全览 ⇄ 特写、上一站/下一站都是「飞过去」
 * - **聚光灯聚焦**当前段（径向渐晕），**行进光点**沿当前段跑，光晕 + 圆头线
 * - 手势：拖动平移、双指缩放、双击切换全览/特写
 */
/**
 * 顶部把手「快速向下甩 ⇒ 收起」的速度阈值（px/s）。
 * 用户 2026-10-01：展开时按住高度调节快速下滑，应该等价于右上角的收起按钮。
 */
private const val FLING_COLLAPSE_VELOCITY = 900f

@Composable
fun RouteMiniMap(
    route: PickupRoute,
    currentStop: Int,
    detail: GuideDetail,
    modifier: Modifier = Modifier,
    initialView: GuideMapView = GuideMapView.OVERVIEW,
    showControls: Boolean = true,
    collapsed: Boolean = false,
    /** 收起/展开地图；**不传就不显示右下角那个收起箭头**（地图取件页整页就是地图，收起没有意义， 之前那个箭头点了没反应 —— 用户 2026-10-01 反馈） */
    onCollapsedChange: ((Boolean) -> Unit)? = null,
    onCurrentStopChange: (Int) -> Unit = {},
    /** 非空时在控制行显示「全屏 / 收起」按钮 */
    onExpand: (() -> Unit)? = null,
    expandLabel: String = "全屏",
    /** 货格号 → 件号标签（首页用**稳定件号**，与卡片 ①②③ 一致；不传则用访问顺序） */
    pickupLabels: Map<String, String> = emptyMap(),
    /** 点一下地图（全屏模式下不传，避免误关） */
    onMapTap: (() -> Unit)? = null,
    /** 全屏时在地图上**货架旁直接写取件码** */
    showStopCodes: Boolean = false,
    /** 已取件的灰点（同货架的连续取件点会合并成一枚，标号写成 `1·2`） */
    completedMarkers: List<CompletedMarker> = emptyList(),
    /** 顶部把手上下拖动时回调（dy 为像素位移，向上为负）——用于调窗格高度 */
    onResizeDelta: ((Float) -> Unit)? = null,
) {
    val dark = isSystemInDarkTheme()
    val pal = remember(dark) { if (dark) MapPalette.DARK else MapPalette.LIGHT }
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    // 🔴 拖动回调必须取**最新**的那一个：`pointerInput`/`draggable` 里的闭包若只捕获首次组合的
    //    实例，调用方算高度时用的是旧值 ⇒ 拖动「失效」（用户 2026-10-01）。
    val resizeCb by rememberUpdatedState(onResizeDelta)

    val stops = route.stops
    val idx = if (stops.isEmpty()) 0 else currentStop.coerceIn(0, stops.size - 1)
    val stop = stops.getOrNull(idx)
    val target = (stop as? RouteStop.Pickup)?.spot
    val leg = route.legs.getOrNull(idx)
    val hints = remember(route, idx, target) { leg?.let { VenueGuide.describe(it, target) } ?: emptyList() }
    val mainHints = hints.filter { it.kind == VenueGuide.HintKind.MOVE || it.kind == VenueGuide.HintKind.STUB_IN }
    // NOTE 类注记（走廊名/地标补充）不再显示 —— 用户 2026-10-01：那行灰色小字无意义
    val oneLine = mainHints.joinToString(" → ") { if (detail == GuideDetail.FULL) it.text else it.brief }

    // 标记文字：取件用件号（稳定件号优先），顺丰/出站用徽标；**同货架的连续取件合并成一枚**
    val markerLabels = remember(route, pickupLabels) {
        var n = 0
        route.stops.map { s ->
            when (s) {
                is RouteStop.Pickup -> {
                    n += 1
                    pickupLabels[s.code.toString()] ?: n.toString()
                }
                RouteStop.SfCheckout -> "SF"
                is RouteStop.Exit -> "出"
            }
        }
    }
    val groups = remember(route) { groupRouteStops(route) }

    var view by remember(initialView) { mutableStateOf(initialView) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 相机：格子坐标 + 每格像素；三轴用同一个缓动并联飞行
    val camX = remember { Animatable(64f) }
    val camY = remember { Animatable(36f) }
    val camScale = remember { Animatable(6f) }
    val ease = remember { CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f) }
    val flyMs = 560

    LaunchedEffect(view, idx, canvasSize, route) {
        if (canvasSize.width == 0 || stops.isEmpty()) return@LaunchedEffect
        val t = if (view == GuideMapView.CLOSEUP) {
            fitBounds(legBounds(route, idx), canvasSize)
        } else {
            fitAll(canvasSize)
        }
        coroutineScope {
            launch { camX.animateTo(t.cx, tween(flyMs, easing = ease)) }
            launch { camY.animateTo(t.cy, tween(flyMs, easing = ease)) }
            launch { camScale.animateTo(t.scale, tween(flyMs, easing = ease)) }
        }
    }

    // 行进光点 + 当前站呼吸：一个无限动画驱动
    val transition = rememberInfiniteTransition(label = "mapMotion")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "phase",
    )

    val dots = remember(canvasSize) { buildDots(canvasSize) }
    // 入口标志固定画在**真正的入口闸机**上（不要跟着起点跑）；起点不同时再单独画一个「我」
    val entranceCell = remember { siteEntranceCell() }

    if (collapsed) {
        // 收起态：**整颗胶囊**（全圆角、无硬边），不要再像一块被切掉的方卡。
        // 🔴 用户 2026-10-01：**点整颗胶囊**就应该展开（原来只有右边那个 ▲ 小按钮能点）
        Card(
            modifier = modifier.then(
                if (onCollapsedChange != null) {
                    Modifier.clickable { onCollapsedChange.invoke(false) }
                } else {
                    Modifier
                }
            ),
            shape = Corners.pillShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stopColor(stop, pal)),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        text = titleOf(route, idx),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (mainHints.isNotEmpty()) {
                        Text(
                            text = oneLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { onCollapsedChange?.invoke(false) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "展开地图")
                }
            }
        }
        return
    }

    Card(
        modifier = modifier,
        shape = Corners.cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部把手：上下拖动可调窗格高度（高度由调用方持久化）；
            // **快速向下甩**等价于右上角的「收起」（用户 2026-10-01）。
            if (onResizeDelta != null) {
                val resizeState = rememberDraggableState { dy -> resizeCb?.invoke(dy) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = resizeState,
                            onDragStopped = { velocity ->
                                if (velocity > FLING_COLLAPSE_VELOCITY) onCollapsedChange?.invoke(true)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .clip(Corners.pillShape)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 2.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = titleOf(route, idx),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // 用户 2026-10-01：只留必要信息 —— 「顶部两指缩放/拖动」「双击可聚焦」这类
                        // 操作说明属于噪音（手势本来就该自己会），删掉。
                        text = "全程 ${fmtTiles(route.totalTiles)} 格 · " +
                            if (view == GuideMapView.CLOSEUP) "特写" else "全览",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                    )
                }
                if (showControls && stops.isNotEmpty()) {
                    IconButton(onClick = { onCurrentStopChange(idx - 1) }, enabled = idx > 0) {
                        Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一站")
                    }
                    IconButton(onClick = { onCurrentStopChange(idx + 1) }, enabled = idx < stops.size - 1) {
                        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一站")
                    }
                    TextButton(onClick = {
                        view = if (view == GuideMapView.OVERVIEW) GuideMapView.CLOSEUP else GuideMapView.OVERVIEW
                    }) { Text(if (view == GuideMapView.OVERVIEW) "特写" else "全览") }
                    onExpand?.let { TextButton(onClick = it) { Text(expandLabel) } }
                    // 收起按钮只在调用方给了回调时才显示（地图取件页不传 ⇒ 不显示那个「没用」的箭头）
                    onCollapsedChange?.let { collapse ->
                        IconButton(onClick = { collapse(true) }) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起地图")
                        }
                    }
                }
            }

            // 🔴 用户 2026-10-01：这个紫色提示块里那行**灰色小字**（走廊名/地标的补充注记）无意义 ⇒ 删掉，
            //    只留一行「怎么走」。其他类似的冗余文案也一并清理（见下方标题行的说明）。
            if (mainHints.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = Corners.chipShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = oneLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 10.dp)
                    .clip(Corners.chipShape)
                    .background(pal.canvasBg)
                    .clipToBounds()
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val s = (camScale.value * zoom).coerceIn(2f, 60f)
                            val cx = camX.value - pan.x / s
                            val cy = camY.value - pan.y / s
                            scope.launch {
                                camScale.snapTo(s)
                                camX.snapTo(cx)
                                camY.snapTo(cy)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onMapTap?.invoke() },
                            onDoubleTap = {
                                view = if (view == GuideMapView.OVERVIEW) {
                                    GuideMapView.CLOSEUP
                                } else {
                                    GuideMapView.OVERVIEW
                                }
                            }
                        )
                    }
            ) {
                drawVenue(
                    route = route,
                    idx = idx,
                    cam = Cam(camX.value, camY.value, camScale.value),
                    pal = pal,
                    measurer = measurer,
                    phase = phase,
                    markerLabels = markerLabels,
                    groups = groups,
                    dots = dots,
                    showStopCodes = showStopCodes,
                    completedMarkers = completedMarkers,
                    entranceCell = entranceCell,
                )
            }
        }
    }
}

/**
 * 「下一站 3/7 · D5-23」。
 *
 * 🔴 序号**只数取件站**（用户 2026-10-01：顺丰出库在 `route.stops` 里也占一站，用整体下标会让
 * 地图上的「第 n 站」与卡片上的 ①②③ 错位 —— 顺丰出库之后所有序号都差一位）。
 * 非取件步骤（顺丰出库 / 出站）不参与编号，直接显示自己的名字。
 */
private fun titleOf(route: PickupRoute, idx: Int): String {
    val stops = route.stops
    val stop = stops.getOrNull(idx) ?: return "路线"
    val what = when (stop) {
        is RouteStop.Pickup -> stop.code.toString()
        RouteStop.SfCheckout -> "顺丰出库（专用闸机）"
        is RouteStop.Exit -> "出站：${stop.kind.label}"
    }
    if (stop !is RouteStop.Pickup) return what
    val pickupNo = stops.take(idx + 1).count { it is RouteStop.Pickup }
    val pickupTotal = stops.count { it is RouteStop.Pickup }
    val prefix = if (pickupNo == 1) "下一站" else "第 $pickupNo 站"
    return "$prefix $pickupNo/$pickupTotal · $what"
}

private fun stopColor(stop: RouteStop?, pal: MapPalette): Color = when (stop) {
    is RouteStop.Pickup -> pal.accent
    RouteStop.SfCheckout -> pal.sf
    is RouteStop.Exit -> pal.exit
    null -> pal.accent
}

private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

// ============================================================ 相机与配色

private data class Cam(val cx: Float, val cy: Float, val scale: Float)

private class MapPalette(
    val canvasBg: Color,
    val bgTop: Color,
    val bgBottom: Color,
    val shelf: Color,
    val shelfEdge: Color,
    val gate: Color,
    val gateEdge: Color,
    val entrance: Color,
    val entranceInk: Color,
    val outline: Color,
    val texture: Color,
    val routeDone: Color,
    val routeTodo: Color,
    val accent: Color,
    val sf: Color,
    val exit: Color,
    val scrim: Color,
    val shadow: Color,
    val label: Color,
    val codeBg: Color,
    val codeInk: Color,
) {
    companion object {
        /** 原型 A：清爽浅色 */
        val LIGHT = MapPalette(
            canvasBg = Color(0xFFF8FBFF),
            bgTop = Color(0xFFF8FBFF),
            bgBottom = Color(0xFFEFF4FB),
            shelf = Color(0xFFE7EDF6),
            shelfEdge = Color(0xFFD2DCE9),
            gate = Color(0xFFFDEBD2),
            gateEdge = Color(0xFFEFC189),
            entrance = Color(0xFFB7E3BF),
            entranceInk = Color(0xFF155724),
            outline = Color(0x6680A0C8),
            texture = Color(0x12345C8C),
            routeDone = Color(0xFFB9C4D0),
            routeTodo = Color(0xFF8FBCF5),
            accent = Color(0xFF2F6FE4),
            sf = Color(0xFFF07A2B),
            exit = Color(0xFFD0483C),
            scrim = Color(0xCCEEF2F8),
            shadow = Color(0x1A1C3258),
            label = Color(0xFF5B6B7C),
            codeBg = Color(0xF2FFFFFF),
            codeInk = Color(0xFF16202C),
        )

        /** 原型 B：夜跑深色（不含通道横格） */
        val DARK = MapPalette(
            canvasBg = Color(0xFF0F1724),
            bgTop = Color(0xFF131C2B),
            bgBottom = Color(0xFF0F1724),
            shelf = Color(0xFF26344A),
            shelfEdge = Color(0xFF3A4C68),
            gate = Color(0xFF4A3A22),
            gateEdge = Color(0xFF7C5F2C),
            entrance = Color(0xFF255C3B),
            entranceInk = Color(0xFFB9F0C6),
            outline = Color(0x5980A0C8),
            texture = Color(0x14FFFFFF),
            routeDone = Color(0xFF39465A),
            routeTodo = Color(0xFF43628F),
            accent = Color(0xFF5B95F5),
            sf = Color(0xFFF0904A),
            exit = Color(0xFFE06A5E),
            scrim = Color(0xBD080C14),
            shadow = Color(0x55000000),
            label = Color(0xFF8FA2B8),
            codeBg = Color(0xE6151D2B),
            codeInk = Color(0xFFE8EEF7),
        )
    }
}

/** 全览：整个场地；特写：当前段（含上一站）包围盒。 */
private fun siteBounds() = floatArrayOf(
    SiteData.MIN_COL.toFloat(), SiteData.MAX_COL.toFloat(),
    SiteData.MIN_ROW.toFloat(), SiteData.MAX_ROW.toFloat(),
)

private fun legBounds(route: PickupRoute, idx: Int): FloatArray {
    val focus = ArrayList<GridCell>()
    route.legs.getOrNull(idx)?.cells?.let { focus.addAll(it) }
    route.legs.getOrNull(idx - 1)?.cells?.lastOrNull()?.let { focus.add(it) }
    if (focus.isEmpty()) return siteBounds()
    val pad = 6
    return floatArrayOf(
        (focus.minOf { it.col } - pad).coerceAtLeast(SiteData.MIN_COL).toFloat(),
        (focus.maxOf { it.col } + pad).coerceAtMost(SiteData.MAX_COL).toFloat(),
        (focus.minOf { it.row } - pad).coerceAtLeast(SiteData.MIN_ROW).toFloat(),
        (focus.maxOf { it.row } + pad).coerceAtMost(SiteData.MAX_ROW).toFloat(),
    )
}

private fun fitAll(size: IntSize): Cam {
    val b = siteBounds()
    return fitBounds(b, size)
}

private fun fitBounds(b: FloatArray, size: IntSize): Cam {
    val cols = (b[1] - b[0] + 1f).coerceAtLeast(1f)
    val rows = (b[3] - b[2] + 1f).coerceAtLeast(1f)
    val pad = 26f
    val w = (size.width - pad * 2).coerceAtLeast(40f)
    val h = (size.height - pad * 2).coerceAtLeast(40f)
    val scale = minOf(w / cols, h / rows).coerceIn(2f, 60f)
    return Cam((b[0] + b[1] + 1f) / 2f, (b[2] + b[3] + 1f) / 2f, scale)
}

/** 点阵底纹（与原型一致；每 14px 一个点）。 */
private fun buildDots(size: IntSize): List<Offset> {
    if (size.width <= 0 || size.height <= 0) return emptyList()
    val out = ArrayList<Offset>(1024)
    var y = 7f
    while (y < size.height) {
        var x = 7f
        while (x < size.width) {
            out.add(Offset(x, y))
            x += 14f
        }
        y += 14f
    }
    return out
}

// ============================================================ 绘制

private fun DrawScope.drawVenue(
    route: PickupRoute,
    idx: Int,
    cam: Cam,
    pal: MapPalette,
    measurer: TextMeasurer,
    phase: Float,
    markerLabels: List<String>,
    groups: List<StopGroup>,
    dots: List<Offset>,
    showStopCodes: Boolean,
    completedMarkers: List<CompletedMarker>,
    entranceCell: GridCell?,
) {
    fun px(col: Float): Float = size.width / 2f + (col - cam.cx) * cam.scale
    fun py(row: Float): Float = size.height / 2f + (row - cam.cy) * cam.scale
    val cell = cam.scale

    // ① 背景（柔和竖向渐变）
    drawRect(Brush.verticalGradient(listOf(pal.bgTop, pal.bgBottom)))

    // ② 点阵底纹
    if (dots.isNotEmpty()) {
        drawPoints(
            points = dots,
            pointMode = PointMode.Points,
            color = pal.texture,
            strokeWidth = 2.4f,
            cap = StrokeCap.Round,
        )
    }

    // ③ 虚线场地外框（让图不飘在白底上）
    val siteL = px(SiteData.MIN_COL.toFloat()) - 4f
    val siteT = py(SiteData.MIN_ROW.toFloat()) - 4f
    val siteR = px((SiteData.MAX_COL + 1).toFloat()) + 4f
    val siteB = py((SiteData.MAX_ROW + 1).toFloat()) + 4f
    drawRoundRect(
        color = pal.outline,
        topLeft = Offset(siteL, siteT),
        size = Size(siteW(siteL, siteR), siteH(siteT, siteB)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f),
        style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
    )

    // ④ 只画货架 / 柜列 / 区域（带描边与极淡阴影）；通道格不再铺色
    val radius = (cell / 2.6f).coerceIn(1.5f, 7f)
    var i = 0
    while (i < SiteData.rectBounds.size) {
        val c0 = SiteData.rectBounds[i]
        val c1 = SiteData.rectBounds[i + 1]
        val r0 = SiteData.rectBounds[i + 2]
        val r1 = SiteData.rectBounds[i + 3]
        val x = px(c0.toFloat())
        val y = py(r0.toFloat())
        val w = (c1 - c0 + 1) * cell
        val h = (r1 - r0 + 1) * cell
        if (x <= size.width + 40f && y <= size.height + 40f && x + w >= -40f && y + h >= -40f) {
            val isGate = SiteData.rectLabels.getOrNull(i / 4)?.let { lab ->
                lab.contains("闸机") || lab.contains("出口")
            } == true
            drawRoundRect(
                color = pal.shadow,
                topLeft = Offset(x, y + 2f),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            )
            drawRoundRect(
                color = if (isGate) pal.gate else pal.shelf,
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
            )
            drawRoundRect(
                color = if (isGate) pal.gateEdge else pal.shelfEdge,
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                style = Stroke(width = 1f),
            )
            // 🔴 rectBounds 每 4 个数一个矩形，而 rectLabels 每个矩形一个 ⇒ 标签下标必须是 i/4
            //    （写 i 会让所有货架名错位 —— 用户 2026-10-01 反馈「货架号标注几乎全乱」的根因）
            val label = SiteData.rectLabels.getOrNull(i / 4)?.trim().orEmpty()
            if (cell >= 11f && label.isNotEmpty()) {
                val style = TextStyle(color = pal.label, fontSize = 9.sp)
                // 🔴 只有闸机带才竖排（用户 2026-10-01）：J/J1 这种短标签之前也被竖排，J 和数字之间空一大截，
                //    看起来像被劈开。普通货架标签一律横排。
                if (isGate) {
                    val laid = label.map { measurer.measure(it.toString(), style = style, maxLines = 1) }
                    val lineH = (laid.maxOfOrNull { it.size.height } ?: 10) + 1f
                    var yy = y + h / 2f - laid.size * lineH / 2f
                    laid.forEach { l ->
                        drawText(l, topLeft = Offset(x + w / 2f - l.size.width / 2f, yy))
                        yy += lineH
                    }
                } else {
                    val layout = measurer.measure(label, style = style, maxLines = 1)
                    drawText(
                        layout,
                        topLeft = Offset(x + w / 2f - layout.size.width / 2f, y + h / 2f - layout.size.height / 2f),
                    )
                }
            }
        }
        i += 4
    }
    // 入口
    i = 0
    while (i < SiteData.entranceSpans.size) {
        val row = SiteData.entranceSpans[i]
        val c0 = SiteData.entranceSpans[i + 1]
        val c1 = SiteData.entranceSpans[i + 2]
        drawRoundRect(
            color = pal.entrance,
            topLeft = Offset(px(c0.toFloat()), py(row.toFloat())),
            size = Size((c1 - c0 + 1) * cell, cell),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
        )
        i += 3
    }

    // ⑤ 路线：已走过（灰）/ 当前段（蓝 + 光晕）/ 之后（浅蓝），圆头线
    val lineW = (cell * 0.8f).coerceIn(2.5f, 7f)
    route.legs.forEachIndexed { legIndex, leg ->
        val cells = leg.cells
        if (cells.size < 2) return@forEachIndexed
        val color = when {
            legIndex < idx -> pal.routeDone
            legIndex == idx -> pal.accent
            else -> pal.routeTodo
        }
        val w = if (legIndex == idx) lineW else lineW * 0.8f
        if (legIndex == idx) {
            drawPathOf(cells, ::px, ::py, cell, pal.accent.copy(alpha = 0.18f), w * 2.6f)
        }
        drawPathOf(cells, ::px, ::py, cell, color, w)
    }

    // ⑥ 行进光点：沿当前段跑（像外卖 App）
    route.legs.getOrNull(idx)?.cells?.takeIf { it.size > 1 }?.let { cells ->
        val k = phase * (cells.size - 1)
        val i0 = k.toInt().coerceIn(0, cells.size - 1)
        val f = k - i0
        val a = cells[i0]
        val b = cells[(i0 + 1).coerceAtMost(cells.size - 1)]
        val x = px(a.col + (b.col - a.col) * f + 0.5f)
        val y = py(a.row + (b.row - a.row) * f + 0.5f)
        val halo = (cell * 2.2f).coerceIn(8f, 22f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(pal.accent.copy(alpha = 0.40f), Color.Transparent),
                center = Offset(x, y),
                radius = halo,
            ),
            radius = halo,
            center = Offset(x, y),
        )
        drawCircle(Color.White, radius = (cell * 0.75f).coerceIn(3f, 7f), center = Offset(x, y))
        drawCircle(pal.accent, radius = (cell * 0.5f).coerceIn(2f, 5f), center = Offset(x, y))
    }

    // ⑦ 聚光灯：把当前段以外的区域柔和压暗（径向渐晕）
    run {
        val b = legBounds(route, idx)
        val cx = (px(b[0]) + px(b[1] + 1f)) / 2f
        val cy = (py(b[2]) + py(b[3] + 1f)) / 2f
        val rx = (px(b[1] + 1f) - px(b[0])) / 2f + 46f
        val ry = (py(b[3] + 1f) - py(b[2])) / 2f + 38f
        val rad = maxOf(rx, ry).coerceAtLeast(70f) * 1.75f
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, pal.scrim),
                center = Offset(cx, cy),
                radius = rad,
            ),
        )
    }

    // ⑦b **方向指示**：圆头 chevron（不是尖三角 —— 用户 2026-10-01：那个三角形又丑又常盖住圆圈）。
    //     🔴 用户又指出箭头**漂移、不在行进路线上**（上一版把它朝右侧偏了 0.9 格）⇒ 现在：
    //     位置 = 当前段路径的**中点**（按实际像素长度折半，必定落在折线上）；
    //     方向 = 中点那一小段的走向（比「首段方向」更准，拐弯后不会指错）。
    //     图层：在聚光灯（⑦）**之后**、站点标记（⑧）**之前** ⇒ 既不会被渐晕压暗，也不会盖住站点圆点。
    route.legs.getOrNull(idx)?.cells?.let { cells ->
        pathMidArrow(cells, ::px, ::py)?.let { (center, dir) ->
            drawChevron(
                center.x,
                center.y,
                dir,
                (cell * 1.1f).coerceIn(7f, 15f),
                pal.accent.copy(alpha = 0.92f),
            )
        }
    }

    // ⑧ 站点标记：**按组合并**（同货架的连续取件只画一枚，标号写成 `1·2`），取件用件号、顺丰/出站用徽标
    groups.forEach { g ->
        val stopIndex = g.indexes.first()
        val stop = route.stops.getOrNull(stopIndex) ?: return@forEach
        val isCurrent = idx in g.indexes
        val x = px(g.cell.col + 0.5f)
        val y = py(g.cell.row + 0.5f)
        val label = if (g.isPickup) {
            g.indexes.joinToString("·") { markerLabels.getOrNull(it) ?: "?" }
        } else {
            markerLabels.getOrNull(stopIndex) ?: "?"
        }
        val color = stopColor(stop, pal)
        val r = (cell * 1.5f).coerceIn(9f, 15f) * if (isCurrent) 1.06f else 1f
        if (isCurrent) {
            val t = ((phase * 1.4f) % 1f)
            drawCircle(pal.accent.copy(alpha = 0.22f * (1f - t)), radius = r * (1.6f + t * 0.9f), center = Offset(x, y))
        }
        drawCircle(pal.shadow, radius = r, center = Offset(x, y + 2f))
        drawCircle(color, radius = r, center = Offset(x, y))
        drawCircle(Color.White, radius = r, center = Offset(x, y), style = Stroke(width = 2f))
        val layout = measurer.measure(
            label,
            style = TextStyle(color = Color.White, fontSize = (r * 0.95f).toSp(), fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
    }

    // ⑧b 已取件的灰点（用户 2026-10-01：不要消失，标成灰色「✓ 已取」）
    completedMarkers.forEach { m ->
        val x = px(m.cell.col + 0.5f)
        val y = py(m.cell.row + 0.5f)
        val r = (cell * 1.4f).coerceIn(8f, 14f)
        drawCircle(pal.routeDone, radius = r, center = Offset(x, y))
        drawCircle(Color.White, radius = r, center = Offset(x, y), style = Stroke(width = 2f))
        val layout = measurer.measure(
            "✓",
            style = TextStyle(color = Color.White, fontSize = (r * 1.0f).toSp(), fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
        // 旁边标一下是哪个码（小字，避免「这个灰点是谁」）
        val tag = measurer.measure(
            m.label,
            style = TextStyle(color = pal.label, fontSize = 9.sp),
            maxLines = 1,
        )
        drawText(tag, topLeft = Offset(x + r + 3f, y - tag.size.height / 2f))
    }

    // ⑨ 入口标记：**固定画在真正的入口闸机格上**（绿色圆角方块 + 入）
    entranceCell?.let { e ->
        val x = px(e.col + 0.5f)
        val y = py(e.row + 0.5f)
        val r = (cell * 1.4f).coerceIn(9f, 15f)
        drawRoundRect(
            color = pal.entrance,
            topLeft = Offset(x - r, y - r),
            size = Size(r * 2, r * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.5f, r * 0.5f),
        )
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(x - r, y - r),
            size = Size(r * 2, r * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.5f, r * 0.5f),
            style = Stroke(width = 2f),
        )
        val layout = measurer.measure(
            "入",
            style = TextStyle(color = pal.entranceInk, fontSize = (r * 0.95f).toSp(), fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
    }

    // ⑨b **当前位置**（= 路线起点；刚取完时就是那一件）：蓝圈 +「我」；与入口重合就不重复画
    val startCell = route.legs.firstOrNull()?.cells?.firstOrNull()
    if (startCell != null && startCell != entranceCell) {
        val x = px(startCell.col + 0.5f)
        val y = py(startCell.row + 0.5f)
        val r = (cell * 1.5f).coerceIn(9f, 16f)
        val t = ((phase * 1.2f) % 1f)
        drawCircle(pal.accent.copy(alpha = 0.20f * (1f - t)), radius = r * (1.7f + t), center = Offset(x, y))
        drawCircle(pal.accent, radius = r, center = Offset(x, y))
        drawCircle(Color.White, radius = r, center = Offset(x, y), style = Stroke(width = 2f))
        val layout = measurer.measure(
            "我",
            style = TextStyle(color = Color.White, fontSize = (r * 0.95f).toSp(), fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f))
    }

    // ⑩ 方向指示已上移到 ⑤b（画在站点标记**之下**，且用圆头 chevron —— 不再盖住圆圈）

    // ⑪ 全屏时在**货架旁直接写取件码**：按**组**写，同一货架的多件一起写出来
    //    （用户 2026-10-01：同货架 ≥2 件时以前只显示第一个，其余的看不到）
    if (showStopCodes) {
        val pickGroups = groups.filter { it.isPickup }
        val chosen = if (pickGroups.size <= 14) {
            pickGroups
        } else {
            pickGroups.filter { g -> g.indexes.any { it >= idx } }.take(3).ifEmpty { pickGroups.takeLast(2) }
        }
        val style = TextStyle(color = pal.codeInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        val measureStyle = TextStyle(fontSize = 10.sp)
        val maxLineW = (size.width * 0.46f).coerceAtLeast(120f)
        chosen.forEach { g ->
            val codes = g.indexes.mapNotNull { i -> (route.stops.getOrNull(i) as? RouteStop.Pickup)?.code?.toString() }
            if (codes.isEmpty()) return@forEach
            // 太宽就折行（每个码都不许丢）
            val lines = ArrayList<String>()
            var cur = ""
            codes.forEach { c ->
                val cand = if (cur.isEmpty()) c else "$cur · $c"
                val w = measurer.measure(cand, style = measureStyle, maxLines = 1).size.width
                if (w <= maxLineW || cur.isEmpty()) {
                    cur = cand
                } else {
                    lines += cur
                    cur = c
                }
            }
            if (cur.isNotEmpty()) lines += cur

            val laid = lines.map { measurer.measure(it, style = style, maxLines = 1) }
            val padX = 6f
            val padY = 3f
            val lineH = (laid.maxOfOrNull { it.size.height } ?: 12) + 1f
            val w = (laid.maxOfOrNull { it.size.width } ?: 0) + padX * 2
            val h = lineH * laid.size + padY * 2
            val bx = px(g.cell.col + 0.5f) + (cell * 1.5f).coerceIn(10f, 18f)
            val by = py(g.cell.row + 0.5f) - h / 2f
            val radius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
            drawRoundRect(color = pal.codeBg, topLeft = Offset(bx, by), size = Size(w, h), cornerRadius = radius)
            if (idx in g.indexes) {
                drawRoundRect(
                    color = pal.accent,
                    topLeft = Offset(bx, by),
                    size = Size(w, h),
                    cornerRadius = radius,
                    style = Stroke(width = 1.5f),
                )
            }
            laid.forEachIndexed { li, l ->
                drawText(l, topLeft = Offset(bx + padX, by + padY + li * lineH))
            }
        }
    }
}

private fun siteW(l: Float, r: Float): Float = (r - l).coerceAtLeast(1f)
private fun siteH(t: Float, b: Float): Float = (b - t).coerceAtLeast(1f)

private fun DrawScope.drawPathOf(
    cells: List<GridCell>,
    px: (Float) -> Float,
    py: (Float) -> Float,
    cell: Float,
    color: Color,
    width: Float,
) {
    val path = Path()
    cells.forEachIndexed { i, c ->
        val x = px(c.col + 0.5f)
        val y = py(c.row + 0.5f)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = Stroke(width = width, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/**
 * **路线上**的方向指示位置与走向（用户 2026-10-01：箭头不能漂在路线旁边）。
 *
 * 按实际像素长度把当前段折线**折半**取点 ⇒ 一定落在折线上；方向取该点所在那一小段的走向
 * （拐弯之后不会指错）。段太短（< 2 格）时返回 null（不值得画）。
 */
private fun pathMidArrow(
    cells: List<GridCell>,
    px: (Float) -> Float,
    py: (Float) -> Float,
): Pair<Offset, VenueGuide.Dir>? {
    if (cells.size < 2) return null
    val pts = cells.map { Offset(px(it.col + 0.5f), py(it.row + 0.5f)) }
    var total = 0f
    for (i in 0 until pts.size - 1) total += (pts[i + 1] - pts[i]).getDistance()
    var remain = total / 2f
    for (i in 0 until pts.size - 1) {
        val delta = pts[i + 1] - pts[i]
        val seg = delta.getDistance()
        if (seg <= 0f) continue
        if (remain <= seg) {
            val t = remain / seg
            return Offset(pts[i].x + delta.x * t, pts[i].y + delta.y * t) to dirOf(cells[i], cells[i + 1])
        }
        remain -= seg
    }
    val last = cells.size - 2
    return pts.last() to dirOf(cells[last], cells[last + 1])
}

/** 相邻两格的走向（BFS 路径每步只在一个轴上 ±1）。 */
private fun dirOf(a: GridCell, b: GridCell): VenueGuide.Dir = when {
    b.row < a.row -> VenueGuide.Dir.NORTH
    b.row > a.row -> VenueGuide.Dir.SOUTH
    b.col > a.col -> VenueGuide.Dir.EAST
    else -> VenueGuide.Dir.WEST
}

/** 方向单位向量（北 = 行减小，与 [VenueGuide] 一致）。 */
private fun dirUnit(dir: VenueGuide.Dir): Pair<Float, Float> = when (dir) {
    VenueGuide.Dir.NORTH -> 0f to -1f
    VenueGuide.Dir.SOUTH -> 0f to 1f
    VenueGuide.Dir.EAST -> 1f to 0f
    VenueGuide.Dir.WEST -> -1f to 0f
}

/**
 * 方向指示：**圆头 chevron**（两条圆头线段拼成的「›」）。
 *
 * 用户 2026-10-01 反馈：原来的实心尖三角「非常丑，而且很多时候会覆盖掉那个圆圈」。
 * 现在换成圆头线 + 白色描边（更像导航 App 的走向箭头），并且由调用方把它画在站点标记**之前**。
 */
private fun DrawScope.drawChevron(cx: Float, cy: Float, dir: VenueGuide.Dir, sizePx: Float, color: Color) {
    val (ux, uy) = dirUnit(dir)
    val nx = -uy
    val ny = ux
    val tipX = cx + ux * sizePx * 0.55f
    val tipY = cy + uy * sizePx * 0.55f
    val backX = cx - ux * sizePx * 0.45f
    val backY = cy - uy * sizePx * 0.45f
    val wide = sizePx * 0.62f
    val p = Path().apply {
        moveTo(backX + nx * wide, backY + ny * wide)
        lineTo(tipX, tipY)
        lineTo(backX - nx * wide, backY - ny * wide)
    }
    val w = (sizePx * 0.36f).coerceAtLeast(1.6f)
    drawPath(
        p,
        Color.White,
        style = Stroke(width = w + 2.6f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
    )
    drawPath(
        p,
        color,
        style = Stroke(width = w, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
    )
}
