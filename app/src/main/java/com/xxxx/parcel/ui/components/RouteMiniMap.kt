package com.xxxx.parcel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxxx.parcel.util.GridCell
import com.xxxx.parcel.util.GuideDetail
import com.xxxx.parcel.util.GuideMapView
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.RouteStop
import com.xxxx.parcel.util.SiteData
import com.xxxx.parcel.util.VenueGuide

/**
 * 路线图示窗格（1/3 屏左右，**矢量绘制**，不内嵌任何图片）。
 *
 * 2026-10-01 第二版（按用户反馈改）：
 * - **只画货架格子**：路上那些密密麻麻的通道格不再填充（此前一片蓝，看不清结构），
 *   只留货架/柜列/区域的方块（带描边）＋ 闸机带 ＋ 入口，路线叠在上面
 * - 整块窗格是**一张独立的圆角卡**（自带背景与阴影），不再是一层裸内容压在别的界面上；
 *   提示文字也放进**自己的胶囊**里，不再飘在图上
 * - 可**收起**成一行胶囊（不占地方），控制键换成图标，配色统一到主题色
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
) {
    var view by remember(initialView) { mutableStateOf(initialView) }
    val measurer = rememberTextMeasurer()
    val stops = route.stops
    val idx = if (stops.isEmpty()) 0 else currentStop.coerceIn(0, stops.size - 1)
    val stop = stops.getOrNull(idx)
    val target = (stop as? RouteStop.Pickup)?.spot
    val leg = route.legs.getOrNull(idx)
    val hints = remember(route, idx, target) { leg?.let { VenueGuide.describe(it, target) } ?: emptyList() }
    val mainHints = hints.filter { it.kind == VenueGuide.HintKind.MOVE || it.kind == VenueGuide.HintKind.STUB_IN }
    val nextDir = hints.firstOrNull { it.kind == VenueGuide.HintKind.MOVE }?.dir
    val notes = hints.filter { it.kind == VenueGuide.HintKind.NOTE }
    val oneLine = mainHints.joinToString(" → ") { if (detail == GuideDetail.FULL) it.text else it.brief }

    if (collapsed) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
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
                        .background(stopColor(stop)),
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
                            color = Color(0xFF17458A),
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
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── 标题 + 控制（控制键用图标，少占横向空间）
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
                    IconButton(
                        onClick = { onCurrentStopChange(idx + 1) },
                        enabled = idx < stops.size - 1,
                    ) {
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

            // ── 提示：自己的胶囊（有底色，不飘在图上，也不挤别的界面）
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
                                color = Color(0xFF10366B),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (detail == GuideDetail.FULL && notes.isNotEmpty()) {
                            Text(
                                text = notes.joinToString("；") { it.text },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4A5A6A),
                                maxLines = 3,
                            )
                        }
                    }
                }
            }

            // ── 图：只画货架格子，通道不再铺满（用户 2026-10-01）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 10.dp),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MAP_BG)
                        .clipToBounds()
                ) {
                    drawVenue(route, idx, view, nextDir, measurer)
                }
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

private fun stopColor(stop: RouteStop?): Color = when (stop) {
    is RouteStop.Pickup -> COL_PICK
    RouteStop.SfCheckout -> COL_SF
    is RouteStop.Exit -> COL_EXIT
    null -> COL_PICK
}

private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

// ---------------------------------------------------------------- 绘制

private val MAP_BG = Color(0xFFF6F8FB)
private val COL_SHELF = Color(0xFFE3EAF3)
private val COL_SHELF_EDGE = Color(0xFFC3CFDF)
private val COL_GATE = Color(0xFFFFE8C7)
private val COL_GATE_EDGE = Color(0xFFE9B978)
private val COL_ENTRANCE = Color(0xFFB6E2BB)
private val COL_DONE = Color(0xFFB4BEC9)
private val COL_TODO = Color(0xFFE06A5E)
private val COL_NEXT = Color(0xFF1E6FE0)
private val COL_SF = Color(0xFFE07A28)
private val COL_EXIT = Color(0xFFC0453C)
private val COL_PICK = Color(0xFF2C7BE5)

private fun DrawScope.drawVenue(
    route: PickupRoute,
    idx: Int,
    view: GuideMapView,
    nextDir: VenueGuide.Dir?,
    measurer: TextMeasurer,
) {
    val siteC0 = SiteData.MIN_COL
    val siteC1 = SiteData.MAX_COL
    val siteR0 = SiteData.MIN_ROW
    val siteR1 = SiteData.MAX_ROW

    // 视窗范围：全览 = 整场地；特写 = 当前段（含上一站）包围盒 + 边距
    var bC0 = siteC0
    var bC1 = siteC1
    var bR0 = siteR0
    var bR1 = siteR1
    if (view == GuideMapView.CLOSEUP) {
        val focus = ArrayList<GridCell>()
        route.legs.getOrNull(idx)?.cells?.let { focus.addAll(it) }
        route.legs.getOrNull(idx - 1)?.cells?.lastOrNull()?.let { focus.add(it) }
        if (focus.isNotEmpty()) {
            val pad = 5
            bC0 = (focus.minOf { it.col } - pad).coerceAtLeast(siteC0)
            bC1 = (focus.maxOf { it.col } + pad).coerceAtMost(siteC1)
            bR0 = (focus.minOf { it.row } - pad).coerceAtLeast(siteR0)
            bR1 = (focus.maxOf { it.row } + pad).coerceAtMost(siteR1)
        }
    }
    val cols = (bC1 - bC0 + 1).toFloat()
    val rows = (bR1 - bR0 + 1).toFloat()
    val scale = minOf(size.width / cols, size.height / rows)
    val ox = (size.width - cols * scale) / 2f
    val oy = (size.height - rows * scale) / 2f

    fun px(col: Int): Float = ox + ((col - bC0) * scale)
    fun py(row: Int): Float = oy + ((row - bR0) * scale)

    // 1) 底图：**只画货架/柜列/区域的格子**（带描边）＋ 闸机带 ＋ 入口。
    //    路上那些通道格不再铺底色（用户 2026-10-01：中间的无必要格子去掉，只留货架）
    var i = 0
    while (i < SiteData.rectBounds.size) {
        val c0 = SiteData.rectBounds[i]
        val c1 = SiteData.rectBounds[i + 1]
        val r0 = SiteData.rectBounds[i + 2]
        val r1 = SiteData.rectBounds[i + 3]
        val w = (c1 - c0 + 1) * scale
        val h = (r1 - r0 + 1) * scale
        val topLeft = Offset(px(c0), py(r0))
        val isGate = SiteData.rectLabels.getOrNull(i)?.let { lab ->
            lab.contains("闸机") || lab.contains("出口")
        } == true
        drawRect(
            color = if (isGate) COL_GATE else COL_SHELF,
            topLeft = topLeft,
            size = Size(w, h),
        )
        drawRect(
            color = if (isGate) COL_GATE_EDGE else COL_SHELF_EDGE,
            topLeft = topLeft,
            size = Size(w, h),
            style = Stroke(width = 1f),
        )
        i += 4
    }
    i = 0
    while (i < SiteData.entranceSpans.size) {
        val row = SiteData.entranceSpans[i]
        val c0 = SiteData.entranceSpans[i + 1]
        val c1 = SiteData.entranceSpans[i + 2]
        drawRect(
            COL_ENTRANCE,
            Offset(px(c0), py(row)),
            Size((c1 - c0 + 1) * scale, scale),
        )
        i += 3
    }

    // 2) 路线：未走（浅红）→ 下一段（蓝，带光晕）→ 已走过（浅灰）
    route.legs.forEachIndexed { legIndex, leg ->
        val color = when {
            legIndex < idx -> COL_DONE
            legIndex == idx -> COL_NEXT
            else -> COL_TODO
        }
        val stroke = if (legIndex == idx) (scale * 0.85f).coerceIn(2.5f, 6.5f) else (scale * 0.6f).coerceIn(1.5f, 4f)
        for (k in 0 until leg.cells.size - 1) {
            val a = leg.cells[k]
            val b = leg.cells[k + 1]
            val start = Offset(px(a.col) + scale / 2f, py(a.row) + scale / 2f)
            val end = Offset(px(b.col) + scale / 2f, py(b.row) + scale / 2f)
            if (legIndex == idx) {
                drawLine(COL_NEXT.copy(alpha = 0.18f), start, end, strokeWidth = stroke * 2.6f, cap = StrokeCap.Round)
            }
            drawLine(color, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }

    // 3) 各站圆点：取件 ①②③ / SF 出库 / 出（白圈 + 彩色填充，当前站带光晕）
    var pickupNo = 0
    route.stops.forEachIndexed { stopIndex, stop ->
        val cell = route.legs.getOrNull(stopIndex)?.cells?.lastOrNull() ?: return@forEachIndexed
        val cx = px(cell.col) + scale / 2f
        val cy = py(cell.row) + scale / 2f
        val label: String
        val color: Color
        when (stop) {
            is RouteStop.Pickup -> {
                pickupNo += 1
                label = "$pickupNo"
                color = COL_PICK
            }
            RouteStop.SfCheckout -> {
                label = "SF"
                color = COL_SF
            }
            is RouteStop.Exit -> {
                label = "出"
                color = COL_EXIT
            }
        }
        val radius = (scale * 2.0f).coerceIn(9f, 18f)
        if (stopIndex == idx) drawCircle(COL_NEXT.copy(alpha = 0.20f), radius * 2.0f, Offset(cx, cy))
        drawCircle(color, radius, Offset(cx, cy))
        drawCircle(Color.White, radius, Offset(cx, cy), style = Stroke(width = 2f))
        val layout = measurer.measure(
            label,
            style = TextStyle(color = Color.White, fontSize = (radius * 1.0f).toSp(), fontWeight = FontWeight.Bold),
        )
        drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
    }

    // 4) 入口标记
    val ent = route.legs.firstOrNull()?.cells?.firstOrNull()
    if (ent != null) {
        val cx = px(ent.col) + scale / 2f
        val cy = py(ent.row) + scale / 2f
        val radius = (scale * 1.9f).coerceIn(9f, 16f)
        drawCircle(COL_ENTRANCE, radius, Offset(cx, cy))
        drawCircle(Color.White, radius, Offset(cx, cy), style = Stroke(width = 2f))
        val layout = measurer.measure(
            "入",
            style = TextStyle(color = Color(0xFF1B5E20), fontSize = (radius * 1.0f).toSp(), fontWeight = FontWeight.Bold),
        )
        drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
    }

    // 5) 下一步方向的箭头（放在「下一段」首个同向段的终点，带白描边更醒目）
    if (nextDir != null) {
        firstRunEnd(route.legs.getOrNull(idx)?.cells.orEmpty())?.let { cell ->
            drawArrow(
                px(cell.col) + scale / 2f,
                py(cell.row) + scale / 2f,
                nextDir,
                (scale * 3.0f).coerceIn(12f, 24f),
            )
        }
    }

    // 6) 特写时给货架/区域标名字（全览放不下）
    if (scale >= 11f) {
        var k = 0
        while (k < SiteData.rectLabels.size) {
            val label = SiteData.rectLabels[k].trim()
            val b = k * 4
            val c0 = SiteData.rectBounds[b]
            val c1 = SiteData.rectBounds[b + 1]
            val r0 = SiteData.rectBounds[b + 2]
            val r1 = SiteData.rectBounds[b + 3]
            if (label.isNotEmpty() && c1 >= bC0 && c0 <= bC1 && r1 >= bR0 && r0 <= bR1) {
                val cx = px(c0) + (c1 - c0 + 1) * scale / 2f
                val cy = py(r0) + (r1 - r0 + 1) * scale / 2f
                val layout = measurer.measure(label, style = TextStyle(color = Color(0xFF5B6B7C), fontSize = 9.sp))
                drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
            }
            k++
        }
    }
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

private fun DrawScope.drawArrow(cx: Float, cy: Float, dir: VenueGuide.Dir, sizePx: Float) {
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
    drawPath(p, Color(0xFF0D47A1))
    drawPath(p, Color.White, style = Stroke(width = 2f))
}
