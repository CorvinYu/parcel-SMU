package com.xxxx.parcel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
 * - 底图直接照抄 [SiteData]（用户 Excel 的填充色导出）：可走格（RLE，一段一矩形）、
 *   合并区（货架/区域）、墙、闸机带、入口 —— 改场地数据后图自动跟着变。
 * - 红线 = 全程路线（每段都是 BFS 回溯的**相邻格**序列 ⇒ 不可能斜穿）；
 *   蓝色加粗 = **下一段**；灰线 = 已走过；圆点 = 各站（取件 ①②③ / 橙色 SF 出库 / 红色 出）。
 * - 「全览 ⇄ 特写跟随」一键切换；图上箭头指出下一步方向。
 *
 * ⚠️ 无室内定位能力 ⇒ 站点由用户用「上一站 / 下一站」推进（或点列表里的卡片）。
 */
@Composable
fun RouteMiniMap(
    route: PickupRoute,
    currentStop: Int,
    detail: GuideDetail,
    modifier: Modifier = Modifier,
    initialView: GuideMapView = GuideMapView.OVERVIEW,
    showControls: Boolean = true,
    onCurrentStopChange: (Int) -> Unit = {},
    /** 非空时在控制行显示一个「全屏 / 收起」按钮 */
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

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = titleOf(route, idx),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (mainHints.isNotEmpty()) {
                    Text(
                        text = mainHints.joinToString(" → ") {
                            if (detail == GuideDetail.FULL) it.text else it.brief
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = COL_NEXT,
                        maxLines = 2,
                    )
                }
                if (detail == GuideDetail.FULL && notes.isNotEmpty()) {
                    Text(
                        text = notes.joinToString("；") { it.text },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                    )
                }
            }
            if (showControls && stops.isNotEmpty()) {
                TextButton(onClick = { onCurrentStopChange(idx - 1) }, enabled = idx > 0) { Text("上一站") }
                TextButton(onClick = { onCurrentStopChange(idx + 1) }, enabled = idx < stops.size - 1) { Text("下一站") }
                TextButton(onClick = {
                    view = if (view == GuideMapView.OVERVIEW) GuideMapView.CLOSEUP else GuideMapView.OVERVIEW
                }) { Text(if (view == GuideMapView.OVERVIEW) "特写" else "全览") }
                onExpand?.let { TextButton(onClick = it) { Text(expandLabel) } }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) {
            drawVenue(route, idx, view, nextDir, measurer)
        }
    }
}

/** 「下一站 3/7 · D5-23」 */
private fun titleOf(route: PickupRoute, idx: Int): String {
    val stops = route.stops
    val stop = stops.getOrNull(idx) ?: return "路线"
    val what = when (stop) {
        is RouteStop.Pickup -> stop.code.toString()
        RouteStop.SfCheckout -> "顺丰出库（顺丰专用闸机）"
        is RouteStop.Exit -> "出站：${stop.kind.label}"
    }
    val prefix = if (idx == 0) "下一站" else "第 ${idx + 1} 站"
    return "$prefix ${idx + 1}/${stops.size} · $what"
}

// ---------------------------------------------------------------- 绘制

private val COL_WALK = Color(0xFFD7E6F7)
private val COL_SHELF = Color(0xFFE6E6E6)
private val COL_WALL = Color(0xFFB0B0B0)
private val COL_GATE = Color(0xFFFFE0B2)
private val COL_ENTRANCE = Color(0xFFA5D6A7)
private val COL_DONE = Color(0xFF9E9E9E)
private val COL_TODO = Color(0xFFD32F2F)
private val COL_NEXT = Color(0xFF1565C0)
private val COL_SF = Color(0xFFEA580C)
private val COL_EXIT = Color(0xFFB91C1C)
private val COL_PICK = Color(0xFF1F6FEB)

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

    // 1) 底图：墙 → 可走格 → 合并区 → 闸机 → 入口（RLE/矩形，几十到几百个矩形，不用每格画）
    var i = 0
    while (i < SiteData.wall.size) {
        val row = SiteData.wall[i]
        drawRect(COL_WALL, Offset(px(SiteData.wall[i + 1]), py(row)), Size((SiteData.wall[i + 2] - SiteData.wall[i + 1] + 1) * scale, scale))
        i += 3
    }
    i = 0
    while (i < SiteData.walk.size) {
        val row = SiteData.walk[i]
        drawRect(COL_WALK, Offset(px(SiteData.walk[i + 1]), py(row)), Size((SiteData.walk[i + 2] - SiteData.walk[i + 1] + 1) * scale, scale))
        i += 3
    }
    i = 0
    while (i < SiteData.rectBounds.size) {
        val c0 = SiteData.rectBounds[i]
        val c1 = SiteData.rectBounds[i + 1]
        val r0 = SiteData.rectBounds[i + 2]
        val r1 = SiteData.rectBounds[i + 3]
        drawRect(COL_SHELF, Offset(px(c0), py(r0)), Size((c1 - c0 + 1) * scale, (r1 - r0 + 1) * scale))
        i += 4
    }
    i = 0
    while (i < SiteData.gateSpans.size) {
        val c0 = SiteData.gateSpans[i]
        val c1 = SiteData.gateSpans[i + 1]
        val r0 = SiteData.gateSpans[i + 2]
        val r1 = SiteData.gateSpans[i + 3]
        drawRect(COL_GATE, Offset(px(c0), py(r0)), Size((c1 - c0 + 1) * scale, (r1 - r0 + 1) * scale))
        i += 4
    }
    i = 0
    while (i < SiteData.entranceSpans.size) {
        val row = SiteData.entranceSpans[i]
        drawRect(
            COL_ENTRANCE,
            Offset(px(SiteData.entranceSpans[i + 1]), py(row)),
            Size((SiteData.entranceSpans[i + 2] - SiteData.entranceSpans[i + 1] + 1) * scale, scale),
        )
        i += 3
    }

    // 2) 路线：已走过（灰）/ 下一段（蓝加粗）/ 之后（红）
    route.legs.forEachIndexed { legIndex, leg ->
        val color = when {
            legIndex < idx -> COL_DONE
            legIndex == idx -> COL_NEXT
            else -> COL_TODO
        }
        val stroke = if (legIndex == idx) (scale * 0.9f).coerceIn(2.5f, 7f) else (scale * 0.7f).coerceIn(1.5f, 4.5f)
        for (k in 0 until leg.cells.size - 1) {
            val a = leg.cells[k]
            val b = leg.cells[k + 1]
            drawLine(
                color = color,
                start = Offset(px(a.col) + scale / 2f, py(a.row) + scale / 2f),
                end = Offset(px(b.col) + scale / 2f, py(b.row) + scale / 2f),
                strokeWidth = stroke,
            )
        }
    }

    // 3) 各站圆点：取件 ①②③ / SF 出库 / 出
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
        val radius = (scale * 2.2f).coerceIn(8f, 18f)
        if (stopIndex == idx) drawCircle(COL_NEXT.copy(alpha = 0.22f), radius * 1.9f, Offset(cx, cy))
        drawCircle(color, radius, Offset(cx, cy))
        drawCircle(Color.White, radius, Offset(cx, cy), style = Stroke(width = 1.5f))
        val layout = measurer.measure(
            label,
            style = TextStyle(color = Color.White, fontSize = (radius * 1.05f).toSp(), fontWeight = FontWeight.Bold),
        )
        drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
    }

    // 4) 入口标记
    val ent = route.legs.firstOrNull()?.cells?.firstOrNull()
    if (ent != null) {
        val cx = px(ent.col) + scale / 2f
        val cy = py(ent.row) + scale / 2f
        val radius = (scale * 2.0f).coerceIn(8f, 16f)
        drawCircle(COL_ENTRANCE, radius, Offset(cx, cy))
        drawCircle(Color.White, radius, Offset(cx, cy), style = Stroke(width = 1.5f))
        val layout = measurer.measure(
            "入",
            style = TextStyle(color = Color(0xFF1B5E20), fontSize = (radius * 1.05f).toSp(), fontWeight = FontWeight.Bold),
        )
        drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
    }

    // 5) 下一步方向的箭头（放在「下一段」首个同向段的终点）
    if (nextDir != null) {
        firstRunEnd(route.legs.getOrNull(idx)?.cells.orEmpty())?.let { cell ->
            drawArrow(
                px(cell.col) + scale / 2f,
                py(cell.row) + scale / 2f,
                nextDir,
                (scale * 3.2f).coerceIn(12f, 26f),
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
                val layout = measurer.measure(label, style = TextStyle(color = Color(0xFF555555), fontSize = 9.sp))
                drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
            }
            k++
        }
    }

    // 7) 左上角：件数 / 总格数 / 视图名
    val total = if (route.totalTiles % 1.0 == 0.0) route.totalTiles.toInt().toString() else route.totalTiles.toString()
    val title = measurer.measure(
        "${route.resolvedCount} 件 · 共 $total 格" + if (view == GuideMapView.CLOSEUP) "（特写）" else "（全览）",
        style = TextStyle(color = Color(0xFF37474F), fontSize = 10.sp),
    )
    drawText(title, topLeft = Offset(4f, 2f))
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
}
