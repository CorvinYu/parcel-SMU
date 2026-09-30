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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
@Composable
fun RouteMiniMap(
    route: PickupRoute,
    currentStop: Int,
    detail: GuideDetail,
    modifier: Modifier = Modifier,
    initialView: GuideMapView = GuideMapView.OVERVIEW,
    showControls: Boolean = true,
    collapsed: Boolean = false,
    onCollapsedChange: (Boolean) -> Unit = {},
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

    val stops = route.stops
    val idx = if (stops.isEmpty()) 0 else currentStop.coerceIn(0, stops.size - 1)
    val stop = stops.getOrNull(idx)
    val target = (stop as? RouteStop.Pickup)?.spot
    val leg = route.legs.getOrNull(idx)
    val hints = remember(route, idx, target) { leg?.let { VenueGuide.describe(it, target) } ?: emptyList() }
    val mainHints = hints.filter { it.kind == VenueGuide.HintKind.MOVE || it.kind == VenueGuide.HintKind.STUB_IN }
    val notes = hints.filter { it.kind == VenueGuide.HintKind.NOTE }
    val nextDir = hints.firstOrNull { it.kind == VenueGuide.HintKind.MOVE }?.dir
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

    if (collapsed) {
        // 收起态：**整颗胶囊**（全圆角、无硬边），不要再像一块被切掉的方卡
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(50),
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
                IconButton(onClick = { onCollapsedChange(false) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "展开地图")
                }
            }
        }
        return
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部把手：上下拖动可调窗格高度（高度由调用方持久化）
            if (onResizeDelta != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dy -> onResizeDelta.invoke(dy) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(50))
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
                        text = "全程 ${fmtTiles(route.totalTiles)} 格 · " +
                            if (view == GuideMapView.CLOSEUP) "特写 · 顶部两指缩放/拖动" else "全览 · 双击可聚焦",
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
                    IconButton(onClick = { onCollapsedChange(true) }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起地图")
                    }
                }
            }

            if (mainHints.isNotEmpty() || (detail == GuideDetail.FULL && notes.isNotEmpty())) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        if (mainHints.isNotEmpty()) {
                            Text(
                                text = oneLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (detail == GuideDetail.FULL && notes.isNotEmpty()) {
                            Text(
                                text = notes.joinToString("；") { it.text },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
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
                    nextDir = nextDir,
                    dots = dots,
                    showStopCodes = showStopCodes,
                    completedMarkers = completedMarkers,
                )
            }
        }
    }
}

/** 「下一站 3/7 · D5-23」 */
private fun titleOf(route: PickupRoute, idx: Int): String {
    val stops = route.stops
    val stop = stops.getOrNull(idx) ?: return "路线"
    val what = when (stop) {
        is RouteStop.Pickup -> stop.code.toString()
        RouteStop.SfCheckout -> "顺丰出库（专用闸机）"
        is RouteStop.Exit -> "出站：${stop.kind.label}"
    }
    val prefix = if (idx == 0) "下一站" else "第 ${idx + 1} 站"
    return "$prefix ${idx + 1}/${stops.size} · $what"
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
    nextDir: VenueGuide.Dir?,
    dots: List<Offset>,
    showStopCodes: Boolean,
    completedMarkers: List<CompletedMarker>,
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
                // 又高又窄的（闸机带）文字**竖排** —— 用户 2026-10-01：「7个普通闸机」要纵向排列
                if (isGate || (r1 - r0) > (c1 - c0)) {
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

    // ⑨ 入口标记（绿色圆角方块 + 入）
    route.legs.firstOrNull()?.cells?.firstOrNull()?.let { e ->
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

    // ⑩ 下一步方向箭头（当前段首个同向段的终点）
    if (nextDir != null) {
        firstRunEnd(route.legs.getOrNull(idx)?.cells.orEmpty())?.let { c ->
            drawArrow(
                px(c.col + 0.5f),
                py(c.row + 0.5f),
                nextDir,
                (cell * 1.6f).coerceIn(9f, 20f),
                pal.accent,
            )
        }
    }

    // ⑪ 全屏时在**货架旁直接写取件码**（件数多时只标当前与后两件，避免糊成一片）
    if (showStopCodes) {
        val pickupIndexes = route.stops.indices.filter { route.stops[it] is RouteStop.Pickup }
        val chosen = if (pickupIndexes.size <= 14) {
            pickupIndexes
        } else {
            pickupIndexes.filter { it >= idx }.take(3).ifEmpty { pickupIndexes.takeLast(2) }
        }
        chosen.forEach { si ->
            val st = route.stops.getOrNull(si) as? RouteStop.Pickup ?: return@forEach
            val cellPos = route.legs.getOrNull(si)?.cells?.lastOrNull() ?: return@forEach
            val layout = measurer.measure(
                st.code.toString(),
                style = TextStyle(color = pal.codeInk, fontSize = 10.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            val padX = 6f
            val padY = 3f
            val w = layout.size.width + padX * 2
            val h = layout.size.height + padY * 2
            val bx = px(cellPos.col + 0.5f) + (cell * 1.5f).coerceIn(10f, 18f)
            val by = py(cellPos.row + 0.5f) - h / 2f
            drawRoundRect(
                color = pal.codeBg,
                topLeft = Offset(bx, by),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f),
            )
            if (si == idx) {
                drawRoundRect(
                    color = pal.accent,
                    topLeft = Offset(bx, by),
                    size = Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f),
                    style = Stroke(width = 1.5f),
                )
            }
            drawText(layout, topLeft = Offset(bx + padX, by + padY))
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

/** 首个「同方向段」的终点格（与 [VenueGuide] 的压缩规则一致）。 */
private fun firstRunEnd(cells: List<GridCell>): GridCell? {
    if (cells.size < 2) return null
    val dr = cells[1].row - cells[0].row
    val dc = cells[1].col - cells[0].col
    var j = 0
    while (j + 2 < cells.size) {
        val nr = cells[j + 2].row - cells[j + 1].row
        val nc = cells[j + 2].col - cells[j + 1].col
        if (nr != dr || nc != dc) break
        j++
    }
    return cells[j + 1]
}

private fun DrawScope.drawArrow(cx: Float, cy: Float, dir: VenueGuide.Dir, sizePx: Float, color: Color) {
    val p = Path()
    when (dir) {
        VenueGuide.Dir.NORTH -> {
            p.moveTo(cx, cy - sizePx)
            p.lineTo(cx - sizePx * 0.62f, cy + sizePx * 0.45f)
            p.lineTo(cx + sizePx * 0.62f, cy + sizePx * 0.45f)
        }
        VenueGuide.Dir.SOUTH -> {
            p.moveTo(cx, cy + sizePx)
            p.lineTo(cx - sizePx * 0.62f, cy - sizePx * 0.45f)
            p.lineTo(cx + sizePx * 0.62f, cy - sizePx * 0.45f)
        }
        VenueGuide.Dir.EAST -> {
            p.moveTo(cx + sizePx, cy)
            p.lineTo(cx - sizePx * 0.45f, cy - sizePx * 0.62f)
            p.lineTo(cx - sizePx * 0.45f, cy + sizePx * 0.62f)
        }
        VenueGuide.Dir.WEST -> {
            p.moveTo(cx - sizePx, cy)
            p.lineTo(cx + sizePx * 0.45f, cy - sizePx * 0.62f)
            p.lineTo(cx + sizePx * 0.45f, cy + sizePx * 0.62f)
        }
    }
    p.close()
    drawPath(p, color)
    drawPath(p, Color.White, style = Stroke(width = 2f))
}
