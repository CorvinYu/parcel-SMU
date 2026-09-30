package com.xxxx.parcel.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xxxx.parcel.model.SmsData
import com.xxxx.parcel.ui.theme.Corners
import com.xxxx.parcel.util.CompartmentCode
import com.xxxx.parcel.util.PickupRoute
import com.xxxx.parcel.util.RouteOptions
import com.xxxx.parcel.util.addCompletedIds
import com.xxxx.parcel.util.effectiveCompartmentNumber
import com.xxxx.parcel.util.parseCompartmentCode
import com.xxxx.parcel.util.planPickupRoute
import com.xxxx.parcel.util.removeCompletedId
import com.xxxx.parcel.viewmodel.ParcelViewModel

/**
 * **整段行程**的展示状态：**含已经取掉的**，顺序**冻结**。
 *
 * 用户 2026-10-01：「点一下应当从『当前 1/50』变成『当前 2/50』，不是『当前 1/49』」——
 * 所以这里用「全部快递站取件码（含已取）」一次性规划，标记已取**不会**重排、也不会改分母。
 */
data class TripView(
    val route: PickupRoute,
    /** 取件站：(在 route.stops 里的下标, 取件码)，顺序与地图/路线一致 */
    val pickups: List<Pair<Int, String>>,
    /** 已取的取件码 */
    val completed: Set<String>,
    /** 有效货格号 → 短信（用于取地址、以及标记/取消已取件） */
    val byCompartment: Map<CompartmentCode, SmsData>,
) {
    /** 第一件还没取的（-1 = 全取完） */
    val firstPending: Int get() = pickups.indexOfFirst { it.second !in completed }

    /** 把「用户选中的那一件」夹到合法范围：没选过 / 越界 ⇒ 第一件未取的；都取完 ⇒ 最后一件 */
    fun clampCurrent(selected: Int?): Int =
        selected?.takeIf { it in pickups.indices }
            ?: firstPending.takeIf { it >= 0 }
            ?: pickups.lastIndex.coerceAtLeast(0)

    /** 件下标 → 地图用的站下标（route.stops 的下标） */
    fun stopIndexOf(current: Int): Int = when {
        current in pickups.indices -> pickups[current].first
        pickups.isNotEmpty() -> pickups.last().first
        else -> 0
    }
}

/** 由短信列表算出 [TripView]（地图取件页与首页全屏地图共用同一套口径）。 */
@Composable
fun rememberTripView(successData: List<SmsData>, options: RouteOptions): TripView {
    val tripCodes = remember(successData) {
        successData
            .map { effectiveCompartmentNumber(it.compartmentNumber, it.code) }
            .filter { it.isNotBlank() }
            .distinct()
    }
    val route = remember(tripCodes, options) { planPickupRoute(rawCodes = tripCodes, options = options) }
    val completed = remember(successData) {
        successData
            .filter { it.isCompleted }
            .map { effectiveCompartmentNumber(it.compartmentNumber, it.code) }
            .toSet()
    }
    val pickups = remember(route) {
        route.stops.mapIndexedNotNull { i, s ->
            (s as? com.xxxx.parcel.util.RouteStop.Pickup)?.let { i to it.code.toString() }
        }
    }
    // 🔴 按**有效货格号**建索引（短信里的取件码未必等于货格号，如取件码 2628 / 货格号 S3-2-2628）
    val byCompartment = remember(successData) {
        buildMap {
            successData.forEach { s ->
                parseCompartmentCode(effectiveCompartmentNumber(s.compartmentNumber, s.code))?.let { put(it, s) }
            }
        }
    }
    return remember(route, pickups, completed, byCompartment) {
        TripView(route, pickups, completed, byCompartment)
    }
}

/**
 * 行程顶部整体：**当前取件码卡**（点击标记已取、再点恢复）+ 下方**横向可滑的整段序列**。
 *
 * 用户 2026-10-01：首页地图的「全屏」页也要有这么一整块（与地图取件页完全一致），
 * 所以把它抽成公共组件，两处共用。
 */
@Composable
fun TripStopSection(
    context: Context,
    viewModel: ParcelViewModel,
    view: TripView,
    current: Int?,
    onCurrentChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cur = view.clampCurrent(current)
    val allDone = view.firstPending < 0
    val addressOf: (String) -> String = { code ->
        view.byCompartment[parseCompartmentCode(code)]?.address.orEmpty()
    }

    // 点击当前件：未取 ⇒ 标记（**同货架一起**）；已取 ⇒ 再点一下恢复为未取件
    val toggle: (String) -> Unit = { code ->
        val parsed = parseCompartmentCode(code)
        val target = parsed?.let { view.byCompartment[it] }
        when {
            parsed == null || target == null -> Unit
            target.isCompleted -> removeCompletedId(context, viewModel, target.sms, target.code)
            else -> {
                val shelf = "${parsed.rowLetter.uppercaseChar()}${parsed.shelfNumber}"
                val targets = view.byCompartment.filterKeys { k ->
                    k.zone == parsed.zone && "${k.rowLetter.uppercaseChar()}${k.shelfNumber}" == shelf
                }.values.filterNot { it.isCompleted }.distinct()
                if (targets.isNotEmpty()) {
                    addCompletedIds(context, viewModel, targets.map { it.sms }, targets.map { it.code })
                    // **显式前进一格**（1/50 → 2/50）：从当前位置往后找第一件还没取的
                    val nowDone = view.completed + targets.map { t ->
                        parseCompartmentCode(effectiveCompartmentNumber(t.compartmentNumber, t.code))
                            ?.toString() ?: t.code
                    }
                    val from = if (cur in view.pickups.indices) cur else 0
                    val size = view.pickups.size
                    val next = ((from + 1) until size).firstOrNull { view.pickups[it].second !in nowDone }
                        ?: (0 until size).firstOrNull { view.pickups[it].second !in nowDone }
                    onCurrentChange(next ?: from)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        TripStopCard(
            pickups = view.pickups,
            current = cur,
            completed = view.completed,
            allDone = allDone,
            addressOf = addressOf,
            legTiles = view.route.legs.getOrNull(view.stopIndexOf(cur))?.tiles,
            totalTiles = view.route.totalTiles,
            onJump = { onCurrentChange(it) },
            onToggleCompleted = toggle,
        )
        TripStrip(
            pickups = view.pickups,
            current = cur,
            completed = view.completed,
            onJump = { onCurrentChange(it) },
        )
    }
}

/** 当前件卡：位置（分母固定 = 整段件数）+ 大号取件码 + 地址 + 本段/全程格数。 */
@Composable
private fun TripStopCard(
    pickups: List<Pair<Int, String>>,
    current: Int,
    completed: Set<String>,
    allDone: Boolean,
    addressOf: (String) -> String,
    legTiles: Double?,
    totalTiles: Double,
    onJump: (Int) -> Unit,
    onToggleCompleted: (String) -> Unit,
) {
    if (pickups.isEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            shape = Corners.cardShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("还没有可规划的取件码", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "需要「快递站」页里有带「货格号」的取件码（已经取掉的也会留在序列里）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }
    val idx = current.coerceIn(0, pickups.size - 1)
    val code = pickups[idx].second
    val isDone = code in completed
    val address = addressOf(code)
    var dragX by remember(idx) { mutableFloatStateOf(0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .pointerInput(idx) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragX <= -60f -> onJump(idx + 1)
                            dragX >= 60f -> onJump(idx - 1)
                        }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { _, delta -> dragX += delta },
                )
            }
            .clickable { onToggleCompleted(code) },
        shape = Corners.cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "当前 ${idx + 1}/${pickups.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (isDone) {
                            Text(
                                "已取",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .clip(Corners.chipShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = code,
                        textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (isDone) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onJump(idx - 1) }, enabled = idx > 0) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "上一件")
                }
                IconButton(onClick = { onJump(idx + 1) }, enabled = idx < pickups.size - 1) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "下一件")
                }
            }
            if (address.isNotBlank()) {
                Text(
                    address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (legTiles != null) {
                Text(
                    "本段 ${fmtTiles(legTiles)} 格 · 全程 ${fmtTiles(totalTiles)} 格",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                when {
                    allDone -> "整段都取完了 · 按「出站」指引离开"
                    isDone -> "再次点击可恢复为未取件"
                    else -> "点击标记已取 · 左右滑切换 · 下方可翻看整段"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 整段序列（横向可滑）：`序号 · 取件码`；已取的留在原位、灰 + 删除线，点任意一格可翻回去看。 */
@Composable
private fun TripStrip(
    pickups: List<Pair<Int, String>>,
    current: Int,
    completed: Set<String>,
    onJump: (Int) -> Unit,
) {
    if (pickups.isEmpty()) return
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(current, pickups.size) {
        if (current in pickups.indices) {
            val approx = with(density) { (current * 78).dp.roundToPx() }
            scroll.animateScrollTo(approx.coerceIn(0, scroll.maxValue))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pickups.forEachIndexed { i, pair ->
            val done = pair.second in completed
            val isCur = i == current
            Text(
                text = "${i + 1} · ${pair.second}",
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                color = when {
                    isCur -> MaterialTheme.colorScheme.onPrimary
                    done -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier
                    .clip(Corners.chipShape)
                    .background(
                        when {
                            isCur -> MaterialTheme.colorScheme.primary
                            done -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .clickable { onJump(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private fun fmtTiles(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
